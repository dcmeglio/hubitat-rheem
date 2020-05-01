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

@Field static String apiUrl = "https://econet-api.rheemcert.com"

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
    
	schedule("0/30 * * * * ? *", updateDevices)
}

def getDevices() { 	 
	state.deviceList = [:]
	def result = apiGet("/locations") 
	if (result != null) {
		result.equipment[0].each { 
			if (it.type.equals("Water Heater")) {
				state.deviceList[it.id.toString()]= it.name
			}
		}
    }
    return state.deviceList
}

def getModes(id) {
	if (state.deviceModes == null)
		state.deviceModes = [:]
	def result = apiGet("/equipment/${id}/modes")
	if (result != null) {
		state.deviceModes[id] = result
	}
}

def hasMode(id, mode) {
	def modes = state.deviceModes[id]
	
	for (deviceMode in modes) {
		if (deviceMode.name == mode)
			return true
	}
	return false
}

def createChildDevices() {
	for (waterHeater in waterHeaters)
	{
		getModes(waterHeater)
		def device = getChildDevice("rheem:" + waterHeater)
		if (!device)
		{
			addChildDevice("dcm.rheem", "Rheem Econet Water Heater", "rheem:" + waterHeater, 1234, ["name": state.deviceList[waterHeater], isComponent: false])
		}
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

def handleRefresh(device, id) {
	logDebug "Refreshing data for ${id}"
	def data = apiGet("/equipment/${id}")
	if (data) {
		device.sendEvent(name: "heatingSetpoint", value: data.setPoint, unit: "F")
		device.sendEvent(name: "thermostatSetpoint", value: data.setPoint, unit: "F")
		device.sendEvent(name: "thermostatOperatingState", value: data.inUse ? "heating" : "idle")		
		device.sendEvent(name: "thermostatMode", value: translateThermostatMode(data.mode))
		if (data.upperTemp != null) {
			device.sendEvent(name: "temperature", value: data.upperTemp.toInteger(), unit: "F")
			device.sendEvent(name: "upperTemp", value: data.upperTemp.toInteger(), unit: "F")
		}
		if (data.lowerTemp != null)
			device.sendEvent(name: "lowerTemp", value: data.lowerTemp.toInteger(), unit: "F")
		if (data.ambientTemp != null)
			device.sendEvent(name: "ambientTemp", value: data.ambientTemp.toInteger(), unit: "F")
		device.sendEvent(name: "waterHeaterMode", value: data.mode)
		device.updateDataValue("minTemp", data.minSetPoint.toString())
		device.updateDataValue("maxTemp", data.maxSetPoint.toString())
	}
}

def handlesetHeatingSetPoint(device, id, temperature) {
	def minTemp = new BigDecimal(device.getDataValue("minTemp"))
	def maxTemp = new BigDecimal(device.getDataValue("maxTemp"))
	if (temperature < minTemp)
		temperature = minTemp
	else if (temperature > maxTemp)
		temperature = maxTemp
	
	apiPutWithRetry("/equipment/${id}", [setPoint: temperature])
}

def handlesetThermostatMode(device, id, thermostatmode) {
	def waterHeaterMode = ""
	switch (thermostatmode) {
		case "off":
			waterHeaterMode = "Off"
			break
		case "cool":
			log.error "Cooling mode not supported"
			return
		case "heat":
			if (hasMode(id, "Heat Pump"))
				waterHeaterMode = "Heat Pump"
			else if (hasMode(id, "Heat Pump Only"))
				waterHeaterMode = "Heat Pump Only"
			else if (hasMode("Electric"))
				waterHeaterMode = "Electric"
			else if (hasMode("Electric-Only"))
				waterHeaterMode = "Electric-Only"
			else if (hasMode("gas"))
				waterHeaterMode = "gas"
			else if (hasMode("Gas"))
				waterHeaterMode = "Gas"
				break
		case "auto":
			waterHeaterMode = "Energy Saver"
			break
		case "emergency heat":
			if (hasMode(id, "High Demand"))
				waterHeaterMode = "High Demand"
			else
				waterHeaterMode = "Performance"
			break
		default:
			waterHeaterMode = thermostatmode
	}
	
	apiPutWithRetry("/equipment/${id}/modes", [mode: waterHeaterMode])
}

def handlesetWaterHeatertMode(device, id, waterheatermode) {
	def mode = waterheatermode
	if (waterheatermode == "Heat Pump") {
		if (!hasMode(id, "Heat Pump"))
			mode = "Heat Pump Only"
	}
	else if (waterheatermode == "Normal") {
		if (hasMode(id, "Electric"))
			mode = "Electric"
		else if (hasMode(id, "Electric-Only"))
			mode = "Electric-Only"
		else if (hasMode(id, "Gas"))
			mode = "Gas"
		else if (hasMode(id, "gas"))
			mode = "gas"
	}
	else if (waterHeater == "High Demand") {
		if (!hasMode(id, "High Demand"))
			mode = "Performance"
	}
	
	apiPutWithRetry("/equipment/${id}/modes", [mode: mode])
}

def updateDevices() {
	for (waterHeater in waterHeaters)
	{
		handleRefresh(getChildDevice("rheem:"+waterHeater), waterHeater)
	}
}

def translateThermostatMode(mode) {
	switch (mode) {
		case "Energy Saver":
			return "auto"
        case "High Demand":
		case "Performance":
            return "emergency heat"
        case "Heat Pump":
		case "Heat Pump Only":
		case "Electric":
		case "Electric-Only":
		case "gas":
		case "Gas":
            return "heat"
		case "Off":
			return "off"
	}
}

def login() {
	def params = [
    	uri: apiUrl,
        path: "/auth/token",
        headers: ["Authorization": "Basic Y29tLnJoZWVtLmVjb25ldF9hcGk6c3RhYmxla2VybmVs"],
        requestContentType: "application/x-www-form-urlencoded"
    ]
    if (state.expiration == null) {
		def result = false
    	try {
			params.body = [
				username: username,
				password: password,
				"grant_type": "password"
			]
			httpPost(params) { resp -> 
            	if (resp.status == 200) {
					state.access_token = resp.data.access_token
					state.refresh_token = resp.data.refresh_token
					state.expiration = now()+(resp.data.expires_in*1000)
                	result = true
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
	else if (state.expiration >= now()-100000)
	{
		return true
	}
	else {
		def result = false
    	try {
			params.body = [
				refresh_token:state.refresh_token,
				"grant_type": "refresh"
			]
			httpPost(params) { resp -> 
            	if (resp.status == 200) {
					state.access_token = resp.data.access_token
					state.refresh_token = resp.data.refresh_token
					state.expiration = now()+(resp.data.expires_in*1000)
                	result = true
            	} else {
                	result = false
            	} 	
        	}
			return result
		}
		catch (e)	{
			log.error "Refresh login error: ${e}"
        	return false
		}	
	}
}

def apiGet(path) {	
	if (login()) {
		def params = [ 
			uri: apiUrl,
			path: path,
			headers: ["Authorization": "Bearer " + state.access_token ],
			requestContentType: "application/json",
		] 

		def result
		try {
			httpGet(params) { resp -> 
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

def apiPut(path, body) {	
	if (login()) {
		def params = [ 
			uri: apiUrl,
			path: path,
			headers: ["Authorization": "Bearer " + state.access_token ],
			requestContentType: "application/json",
			body: body
		]
		logDebug "apiPut: ${params}"
		try {
			def result = null
			httpPut(params) { resp ->
				if (resp.status >= 400) {
					log.error "API Error: ${resp.status}"
					if (resp.status == 401)
						state.expiration = null
				}
				result = resp
			}
			return result
		}
		catch (e)	{
			log.error "API Put Error: ${e}"
			return null
		}
	}
	else {
		return null
	}
}

def apiPutWithRetry(path, body) {	
	def retries = 5
	def result = apiPut(path, body)
	while (result == null) {
		log.error "API Error, retrying ${retries}"
		retries--
		if (retries <= 0)
			return
		pauseExecution(2500)
	}
	return result.data
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