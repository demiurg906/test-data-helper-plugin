/**
* JetBrains Space Automation
* This Kotlin-script file lets you automate build activities
* For more info, see https://www.jetbrains.com/help/space/automation.html
*/

job("Publish Plugin") {
    container(displayName = "Run publish script", image = "gradle") {
        env["PUBLISH_TOKEN"] = Secrets("publish_token")

        kotlinScript { api ->
            api.gradle("publish")
        }
    }
}
