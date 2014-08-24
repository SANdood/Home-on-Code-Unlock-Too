/**
 *  Home Mode on Code Unlock Too
 *
 *  Copyright 2014 Barry A. Burke
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Home on Code Unlock Too",
    namespace: "smartthings",
    author: "Barry A. Burke",
    description: "Change Hello, Home! mode when door is unlocked with a code. Optionally identify the person, send distress message, and/or return to Away mode on departure.",
    category: "Safety & Security",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

import groovy.json.JsonSlurper

preferences {
	page(name: "setupApp")
    page(name: "usersPage")
}

def setupApp() {
    dynamicPage(name: "setupApp", title: "Configure your code and phrases.", install: false, uninstall: true, nextPage: "usersPage") {	
    
		section("What Lock?") {
			input "lock1","capability.lock", title: "Lock"
    	}
        section("How many User Names (1-30)?") {
        	input name: "maxUserNames", title: "# of users", type: "number", required: true, multiple: false,  refreshAfterSelection: true
        }
        
      	section("Controls") {
        	input name: "anonymousAllowed", title: "Allow Unidentified Users?", type: "bool", defaultValue: false, refreshAfterSelection: true
         	input name: "autoLock", title: "Auto-lock Unidentified Users?", type: "bool", defaultValue: false
         	input name: "enableDistressCode", title: "Enable Distress Code?", type: "bool", defaultValue: false, refreshAfterSelection: true
            if (enableDistressCode) {
            	input name: "distressCode", title: "Distress code (1-${maxUserNames})", type: "number", defaultValue: 0, multiple: false, refreshAfterSelection: true
				if ((distressCode > 0) && (distressCode <= maxUserNames)) {
    				input name: "phone1", type: "phone", title: "Phone number to send message to"
                	input name: "notifyAlso", type: "bool", title: "Send ST Notification also?", defaultValue: false
    				input name: "distressMsg", type: "string", title: "Message to send", defaultValue: "Mayday! at ${location.name} - ${lock1.displayName}"
                }
            }
		} 
           
        section("Return to away if none of these are present") {
        	input "presence1", "capability.presenceSensor", title: "Who?", multiple: true, required: false
        }
            
    	def phrases = location.helloHome?.getPhrases()*.label
    	if (phrases) {
       		phrases.sort()
			section("Hello Home actions...") {
				input "homePhrase", "enum", title: "Home Mode Phrase", defaultValue: "I'm Back!", required: true, options: phrases, refreshAfterSelection:true
            	input "awayPhrase", "enum", title: "Away Mode Phrase", defaultValue: "Goodbye!", required: true, options: phrases, refreshAfterSelection:true
        	}        
		}
        section([mobileOnly:true]) {
			label title: "Assign a name for this SmartApp", required: false
			mode title: "Set for specific mode(s)", required: false
		}
	}
}

def usersPage() {
	dynamicPage(name:"usersPage", title: "User / Code List", uninstall: true, install: true) {
    
		section("User Names/Identifiers (1-${maxUserNames})") {
        	for (int i = 1; i <= settings.maxUserNames; i++) {
            	def priorName = settings."userNames${i}"
            	if (priorName) {
                	input name: "userNames${i}", description: "${priorName}", title: "Code #$i Name", defaultValue: "${priorName}", type: string, multiple: false, required: false
				}
                else {
					input name: "userNames${i}", description: "Tap to set", title: "Code #$i Name", type: string, multiple: false, required: false
                }
            }
        }       
	} 
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize()
{
    log.debug "Settings: ${settings}"
//    subscribe(lock1, "lock", doorHandler, [filterEvents: false])
	subscribe(presence1, "presence", presence)
    subscribe(lock1, "lock", doorHandler)
    state.unlockSetHome = false
    state.lastUser = ""
}


def doorHandler(evt)
{
    log.debug "The ${lock1.displayName} lock is ${lock1.latestValue("lock")}."

	if (evt.name == "lock") {
    	if (evt.value == "unlocked") {
	    	if ((evt.data != "") && (evt.data != null)) {					// ...and only if we have extended data
	    		def data = new JsonSlurper().parseText(evt.data) 
            	if ((data.usedCode != "") && (data.usedCode != null)) {		// ...and only iuf we have usedCode data
					if (enableDistressCode) {
						if(data.usedCode == distressCode) {
        					log.info "Distress Message Sent"
        					sendSms(phone1, distressMsg)
                    		if ( notifyAlso ) { sendNotificationEvent( distressMsg ) }
        				}
                    }
	        		if (!state.unlockSetHome) { 										// only if we aren't already unlocked
						Integer i = data.usedCode as Integer
                        log.debug "Unlocked with code ${i}"
                        def foundUser = ""
                        def userName = settings."userNames${i}"
                        if (userName != null) { foundUser = userName }
                        if ((foundUser == "") && settings.anonymousAllowed) { 
                        	foundUser = "Unidentified Person" 
                        }
						if (foundUser != "") { 								// ...
		        			log.debug "${lock1.displayName} unlocked with code ${data.usedCode} - ${foundUser} is Home!"
							if (location.mode != "Home") {  					// Only if we aren't already in Home mode
    	    					sendNotificationEvent("Running \"${homePhrase}\" because ${foundUser} unlocked ${lock1.displayName}.")
                            	state.unlockSetHome = true						// do this first, in case I'm Back unlocks the door too
                                state.lastUser = foundUser
								location.helloHome.execute(settings.homePhrase)	// Wake up the house - we're HOME!!!
                            }
                        }
                        else {
                        	def doorMsg = "Unidentified Code (${i}) used to unlock ${lock1.displayName})"
                            if (autoLock) {
                            	lock1.lock()
                                doorMsg = doorMsg + ", auto-locking"
                            }
                            sendNotificationEvent( doorMsg )
                        }
                    }
                }
            }
        }
        else if (evt.value == "locked") {
        	if (state.unlockSetHome) {							// Should assure that only this instance runs Goodbye!
            	if (presence1.find{it.currentPresence == "present"} == null) {
            		if (location.mode != "Away") {
                		sendNotificationEvent("Running \"${awayPhrase}\" because ${state.lastUser} locked ${lock1.displayName} and nobody else is at home.")
                    	state.unlockSetHome = false								// do this first, in case Goodbye! action locks the door too.
                    	state.lastUser = ""
                        location.helloHome.execute(settings.awayPhrase)
                    }
                }
            }
        }
    }
}
