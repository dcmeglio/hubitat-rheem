/**
 *  Rheem EcoNet Water Heater
 *
 *  Copyright 2020 Dominick Meglio
 *
 *	If you find this useful, donations are always appreciated 
 *	https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url
 *
 */
 
metadata {
    definition (name: "Rheem Econet Water Heater", namespace: "dcm.rheem", author: "dmeglio@gmail.com") {
		capability "Initialize"
		capability "Thermostat"
		capability "Actuator"
		capability "Sensor"
		capability "ThermostatHeatingSetpoint"
		capability "ThermostatSetpoint"
		capability "ThermostatOperatingState"
		capability "ThermostatMode"
		
		command "setWaterHeaterMode", [[name:"Mode*","type":"ENUM","description":"Mode","constraints":["Heat Pump", "Energy Saver", "High Demand", "Normal", "Vacation", "Off"]]]
		
		attribute "waterHeaterMode", "ENUM"
    }
}

import groovy.transform.Field
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.text.SimpleDateFormat

@Field static String apiUrl = "ssl://rheem.clearblade.com:1884"
@Field static String systemKey = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field static String systemSecret = "E2E699CB0BE6C6FADDB1B0BC9A20"

def installed() {
	initialize()
}

def updated() {
	initialize()
}

def mqttConnectUntilSuccessful() {
	try {
		interfaces.mqtt.connect(apiUrl, parent.getClientId(), parent.getAccessToken(), systemKey, cleanSession: false)
		pauseExecution(3000)
		interfaces.mqtt.subscribe("user/${parent.getAccountId()}/device/reported", 2)
		interfaces.mqtt.subscribe("user/${parent.getAccountId()}/device/desired", 2)
		if (state.queuedMessage != null) {
			interfaces.mqtt.publish("user/${parent.getAccountId()}/device/desired", state.queuedMessage)
			state.queuedMessage = null
		 }
		return true
	}
	catch (e)
	{
		log.warn "Lost connection to MQTT, retrying in 15 seconds"
		runIn(15, "mqttConnectUntilSuccessful")
		return false
	}
}

def initialize() {	
	if (device.getDataValue("enabledDisabled") == "true")
		sendEvent(name: "supportedThermostatModes", value: ["off", "heat"])
	else if (device.getDataValue("tempOnly") == "true")
		sendEvent(name: "supportedThermostatModes", value: [])
	else
		sendEvent(name: "supportedThermostatModes", value: ["off", "heat", "emergency heat", "auto"])
	sendEvent(name: "supportedThermostatFanModes", value: [])
	if (interfaces.mqtt.isConnected())
		interfaces.mqtt.disconnect()
	mqttConnectUntilSuccessful()

}

def publishWithRetry(msg) {
	def payload = buildMQTTMessage()
	for (property in msg.keySet()) {
		payload."$property" = msg[property]
	}
	if (interfaces.mqtt.isConnected()) {
		interfaces.mqtt.publish("user/${parent.getAccountId()}/device/desired", JsonOutput.toJson(payload))
	} 
	else {
		log.error "Not connected to MQTT, reconnecting and queuing command"
		state.queuedMessage = JsonOutput.toJson(payload)
		mqttConnectUntilSuccessful()
	}
}

def mqttClientStatus(String message) {
	if (message == "Status: Connection succeeded") {
		parent.logDebug "Connected to MQTT"
	}
	else if (message.contains("Connection lost") || message.contains("Client is not connected") || message.startsWith("Error:")) {
		parent.logDebug "Lost MQTT connection, reconnecting."
		try {
            interfaces.mqtt.disconnect() // Guarantee we're disconnected
        }
        catch (e) {
        }
		mqttConnectUntilSuccessful()
	}
	else
		log.warn "Status: " + message
}

def parse(String message) {
	def topic = interfaces.mqtt.parseMessage(message)
    def payload =  new JsonSlurper().parseText(topic.payload) 

	if ("rheem:" + payload?.device_name + ":" + payload?.serial_number == device.deviceNetworkId) {
		parent.logDebug "MQTT Message was: ${topic.payload}"
		if (payload."@SETPOINT" != null) {
			device.sendEvent(name: "heatingSetpoint", value: payload."@SETPOINT", unit: "F")
			device.sendEvent(name: "thermostatSetpoint", value: payload."@SETPOINT", unit: "F")
		}
		if (device.getDataValue("enabledDisabled") == "true" && payload."@ACTIVE" != null) {
			def mode = payload."@ACTIVE" == true ? "heat" : "off"
			device.sendEvent(name: "thermostatMode", value: mode)
		}
		if (payload."@MODE" != null) {
			if (!payload."@MODE".toString().isInteger()) {
				device.sendEvent(name: "thermostatMode", value: parent.translateThermostatMode(payload."@MODE".status))
				device.sendEvent(name: "waterHeaterMode", value: payload."@MODE".status)
			}
			else {
				def mode = translateEnumToWaterHeaderMode(payload."@MODE")
				device.sendEvent(name: "thermostatMode", value: parent.translateThermostatMode(mode))
				device.sendEvent(name: "waterHeaterMode", value: mode)
			}
		}

		if (payload."@RUNNING" != null) {
			device.sendEvent(name: "thermostatOperatingState", value: payload."@RUNNING" == "Running" ? "heating" : "idle")	
		}
	}
}

def getDeviceName() {
	return device.deviceNetworkId.split(':')[1]
}

def getSerialNumber() {
	return device.deviceNetworkId.split(':')[2]
}

def buildMQTTMessage() {
	def sdf = new SimpleDateFormat("Y-M-d'T'H:m:s.S")
	def payload = [
		transactionId: "ANDROID_"+sdf.format(new Date()),
		device_name: getDeviceName(),
		serial_number: getSerialNumber()
	]
	return payload
}

def setCoolingSetpoint(temperature) {
    log.error "setCoolingSetpoint called but not supported"
}

def setSchedule(obj) {
    log.error "setSchedule called but not supported"
}

def setThermostatFanMode(fanmode) {
    log.error "setThermostatFanMode called but not supported"
}
	
def setHeatingSetpoint(temperature) {
	def minTemp = new BigDecimal(device.getDataValue("minTemp"))
	def maxTemp = new BigDecimal(device.getDataValue("maxTemp"))
	if (temperature < minTemp)
		temperature = minTemp
	else if (temperature > maxTemp)
		temperature = maxTemp

	publishWithRetry(["@SETPOINT": temperature])
}

def setThermostatMode(thermostatmode) {
	if (device.getDataValue("enabledDisabled") == "true") {
		def onOff = thermostatmode == "off" ? 0 : 1
		publishWithRetry(["@ENABLED": onOff])
	}
	else if (device.getDataValue("tempOnly") != "true") {
		publishWithRetry(["@MODE": translateThermostatModeToEnum(thermostatmode)])
	}
	else
		log.error "setThermostatMode called but not supported"
}

def setWaterHeaterMode(waterheatermode) {
	if (device.getDataValue("enabledDisabled") == "true") {
		def onOff = thermostatmode == "off" ? 0 : 1
		publishWithRetry(["@ENABLED": onOff])
	}
	else if (device.getDataValue("tempOnly") != "true") {
		publishWithRetry(["@MODE": translateWaterHeaterModeToEnum(waterheatermode)])
	}
	else
		log.error "setWaterHeaterMode called but not supported"
}

def translateWaterHeaterModeToEnum(waterheatermode) {
	switch (waterheatermode) {
		case "Off":
			return 0
		case "Energy Saver":
			return 1
        case "Heat Pump":
			return 2
		case "High Demand":
            return 3
		case "Normal":
            return 4
		case "Vacation":
			return 5
	}
}

def translateEnumToWaterHeaderMode(enumVal) {
	switch (enumVal) {
		case 0:
			return "OFF"
		case 1:
			return "ENERGY SAVING"
		case 2:
			return "HEAT PUMP ONLY"
		case 3:
			return "HIGH DEMAND"
		case 4:
			return "ELECTRIC"
		case 5:
			return "VACATION"
	}
}

def translateThermostatModeToEnum(waterheatermode) {
	switch (waterheatermode) {
		case "off":
			return 0
		case "auto":
			return 1
        case "heat":
			if (parent.hasHeatPump(device))
				return 2
			return 4
		case "emergency heat":
            return 3
	}
}

def auto() {
	if (device.getDataValue("tempOnly") != "true") {
		setThermostatMode("auto")
	}
	else
		log.error "auto called but not supported"
}

def emergencyHeat() {
	if (device.getDataValue("tempOnly") != "true") {
		setThermostatMode("emergency heat")
	}
	else
		log.error "emergencyHeat called but not supported"
}

def off() {
	if (device.getDataValue("tempOnly") != "true") {
		setThermostatMode("off")
	}
	else
		log.error "off called but not supported"
}

def heat() {
	if (device.getDataValue("tempOnly") != "true") {
		setThermostatMode("heat")
	}
	else
		log.error "heat called but not supported"
}

def cool() {
	log.error "cool called but not supported"
}

def fanAuto() {
	log.error" fanAuto called but not supported"
}

def fanCirculate() {
	log.error" fanCirculate called but not supported"
}

def fanOn() {
	log.error" fanOn called but not supported"
}
