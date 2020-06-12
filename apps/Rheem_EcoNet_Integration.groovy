/**
 *  Rheem EcoNet Integration
 *
 *  Copyright 2020 Dominick Meglio
 *
 *	If you find this useful, donations are always appreciated 
 *	https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url
 *
 */
 
import groovy.transform.Field

@Field static String apiUrl = "https://rheem.clearblade.com"
@Field static String systemKey = "e2e699cb0bb0bbb88fc8858cb5a401"
@Field static String systemSecret = "E2E699CB0BE6C6FADDB1B0BC9A20"

definition(
    name: "Rheem EcoNet Integration",
    namespace: "dcm.rheem",
    author: "Dominick Meglio",
    description: "Integrate Hubitat with Rheem EcoNet Water Heaters",
    category: "My Apps",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
	documentationLink: "https://github.com/dcmeglio/hubitat-rheem/blob/master/README.md")


preferences {
	page(name: "prefAccountAccess", title: "Rheem EcoNet")    
	page(name: "prefListDevice", title: "Rheem EcoNet")
}

def prefAccountAccess() {
	return dynamicPage(name: "prefAccountAccess", title: "Connect to Rheem EcoNet", nextPage:"prefListDevice", uninstall:false, install: false) {
		section("EcoNet Login Information"){
			paragraph "<b>Note: Your username and password are always transmitted securely. However, Hubitat does not currently support MQTTS. As a result, your authentication token is sent to Rheem unencrypted over MQTT. Theoretically someone could setup a sniffer and gain this token which would allow them to control your water heater until it expires. Please be aware of this risk before using this app. This app will be updated once MQTTS is supported.</b>"
			input("username", "email", title: "Username", description: "Rheem EcoNet Username")
			input("password", "password", title: "Password", description: "Rheem EcoNet Password")
		} 
		section("Settings"){
			input("debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false)
		}
		displayFooter()
	}
}

def prefListDevice() {	
	if (login()) {
		def waterHeaterList = getDevices()
		if (waterHeaterList) {
			return dynamicPage(name: "prefListDevice", title: "Devices", install:true, uninstall:true) {
				section("Select which water heaters to monitor"){
					input name: "waterHeaters", type: "enum", required: false, multiple: true, options: waterHeaterList
				}
				displayFooter()
			}
		} else {
			return dynamicPage(name: "prefListDevice", title: "Error", install:false, uninstall:true) {
				section("") { 
					paragraph "No water heaters were found"
				}
				displayFooter()
			}
		}
	} else {
		return dynamicPage(name: "prefListDevice", title: "Error", install:false, uninstall:true) {
			section("") { 
				paragraph "The username and password you entered is incorrect."
			}
			displayFooter()
		}  
	}
}

def installed() {
	logDebug "Installed with settings: ${settings}"
	initialize()
}

def updated() {
	logDebug "Updated with settings: ${settings}"
	unschedule()
	unsubscribe()
	initialize()
}

def uninstalled() {
	logDebug "uninstalling app"
	for (device in getChildDevices())
	{
		deleteChildDevice(device.deviceNetworkId)
	}
}

def initialize() {
	cleanupChildDevices()
	createChildDevices()
}

def getDevices() { 	 
	state.deviceList = [:]
	def result = apiPost("/getLocation") 
	if (result != null) {
		log.debug result.results
		result.results.locations.each { loc ->
			// Yup the API has a typo and called it "equiptments"
			loc.equiptments.each { equip -> 
				if (equip.device_type == "WH") {
					state.deviceList[equip.device_name+":"+equip.serial_number] = equip."@NAME".value
				}
			}
		}
    }
    return state.deviceList
}

def getDeviceDetails(id) {
	def deviceDetails = [:]

	def result = apiPost("/getLocation")
	if (result != null) {
		
		result.results.locations.each { loc ->
			// Yup the API has a typo and called it "equiptments"
			loc.equiptments.each { equip -> 
				if (equip.device_name + ":" + equip.serial_number == id) {
					deviceDetails.modes = equip."@MODE".constraints.enumText
					deviceDetails.currentMode = equip."@MODE".status
					deviceDetails.minTemp = equip."@SETPOINT".constraints.lowerLimit
					deviceDetails.maxTemp = equip."@SETPOINT".constraints.upperLimit
					deviceDetails.setpoint = equip."@SETPOINT".value
					deviceDetails.running = equip."@RUNNING"
				}
			}
		}
    }
	return deviceDetails
}

def hasMode(device, mode) {
	def id = device.deviceNetworkId.replace("rheem:","")
	def modes = state.deviceModes[id]

	for (deviceMode in modes) {
		if (deviceMode == mode)
			return true
	}
	return false
}

def createChildDevices() {
	for (waterHeater in waterHeaters)
	{
		def deviceDetails = getDeviceDetails(waterHeater)
		def device = getChildDevice("rheem:" + waterHeater)
		if (!device)
		{
			device = addChildDevice("dcm.rheem", "Rheem Econet Water Heater", "rheem:" + waterHeater, 1234, ["name": state.deviceList[waterHeater], isComponent: false])
		}
		if (state.devicesModes == null)
			state.deviceModes = [:]
		state.deviceModes[waterHeater] = deviceDetails.modes
		device.updateDataValue("minTemp", deviceDetails.minTemp.toString())
		device.updateDataValue("maxTemp", deviceDetails.maxTemp.toString())
		device.sendEvent(name: "heatingSetpoint", value: deviceDetails.setpoint, unit: "F")
		device.sendEvent(name: "thermostatSetpoint", value: deviceDetails.setpoint, unit: "F")
		device.sendEvent(name: "thermostatOperatingState", value: deviceDetails.running == "Running" ? "heating" : "idle")	
		device.sendEvent(name: "waterHeaterMode", value: deviceDetails.currentMode)
		device.sendEvent(name: "thermostatMode", value: translateThermostatMode(deviceDetails.currentMode))
	}
}

def cleanupChildDevices()
{
	for (device in getChildDevices())
	{
		def deviceId = device.deviceNetworkId.replace("rheem:","")
		
		def deviceFound = false
		for (waterHeater in waterHeaters)
		{
			if (waterHeater == deviceId)
			{
				deviceFound = true
				break
			}
		}
				
		if (deviceFound == true)
			continue
			
		deleteChildDevice(device.deviceNetworkId)
	}
}

def translateThermostatMode(mode) {
	switch (mode) {
		case "ENERGY SAVING":
			return "auto"
        case "HIGH DEMAND":
		case "PERFORMANCE":
            return "emergency heat"
        case "HEAT PUMP":
		case "HEAT PUMP ONLY":
		case "HEAT PUMP ONLY ":
		case "ELECTRIC":
		case "ELECTRIC MODE":
		case "GAS":
            return "heat"
		case "OFF":
			return "off"
	}
}

def getClientId() {
	return getHubUID()
}

def login() {
	def params = [
    	uri: apiUrl,
        path: "/api/v/1/user/auth",
        headers: [
			"ClearBlade-SystemKey": systemKey,
			"ClearBlade-SystemSecret": systemSecret
		],
        requestContentType: "application/json"
    ]

	try {
		params.body = [
			email: username,
			password: password
		]
		httpPost(params) { resp -> 
			if (resp.status == 200) {
				log.debug resp.data
				if (resp.data.options.success) {
					state.access_token = resp.data.user_token
					state.account_id = resp.data.options.account_id
					result = true
				}
				else
					result = false
			} else {
				result = false
			} 	
		}
		return result
	}
	catch (e)	{
		log.error "Login error: ${e}"
		return false
	}
}

def getAccessToken() {
	return state.access_token
}

def getAccountId() {
	return state.account_id
}

def apiPost(path) {	
	if (login()) {
		def params = [ 
			uri: apiUrl,
			path: "/api/v/1/code/${systemKey}${path}",
			timeout: 60,
			headers: [
				"ClearBlade-SystemKey": systemKey,
				"ClearBlade-SystemSecret": systemSecret,
				"ClearBlade-UserToken": state.access_token,
				"User-Agent": "EcoNet/4.0.4 (com.rheem.econetprod; build:3577; iOS 13.5.0) Alamofire/4.9.1"
			],
			requestContentType: "application/json",
		] 
		
		def result
		try {
			httpPost(params) { resp -> 
				if (resp.status >= 400) {
					log.error "API Error: ${resp.status}"
					if (resp.status == 401)
						state.expiration = null
				}
				result = resp.data
			}
		}
		catch (e)	{
			log.error "API Get Error: ${e}"
			return null
		}
		return result
	}
	else
		return null
}

def apiGet(path) {	
	if (login()) {
		def params = [ 
			uri: apiUrl,
			path: "/api/v/1/code/${systemKey}${path}",
			headers: [
				"ClearBlade-SystemKey": systemKey,
				"ClearBlade-SystemSecret": systemSecret,
				"ClearBlade-UserToken": state.access_token,
				"User-Agent": "EcoNet/4.0.4 (com.rheem.econetprod; build:3577; iOS 13.5.0) Alamofire/4.9.1"
			],
			requestContentType: "application/json",
		] 
		
		def result
		try {
			httpPost(params) { resp -> 
				if (resp.status >= 400) {
					log.error "API Error: ${resp.status}"
					if (resp.status == 401)
						state.expiration = null
				}
				result = resp.data
			}
		}
		catch (e)	{
			log.error "API Get Error: ${e}"
			return null
		}
		return result
	}
	else
		return null
}

def logDebug(msg) {
    if (settings?.debugOutput) {
		log.debug msg
	}
}

def displayFooter(){
	section() {
		paragraph getFormat("line")
		paragraph "<div style='color:#1A77C9;text-align:center'>Rheem EcoNet Integration<br><a href='https://www.paypal.com/cgi-bin/webscr?cmd=_s-xclick&hosted_button_id=7LBRPJRLJSDDN&source=url' target='_blank'><img src='https://www.paypalobjects.com/webstatic/mktg/logo/pp_cc_mark_37x23.jpg' border='0' alt='PayPal Logo'></a><br><br>Please consider donating. This app took a lot of work to make.<br>If you find it valuable, I'd certainly appreciate it!</div>"
	}       
}

def getFormat(type, myText=""){			// Modified from @Stephack Code   
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
}