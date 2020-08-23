package com.example.signedinpubsub

import com.amazonaws.regions.Regions

class Constants {
    companion object{
        const val LOG_TAG = "SignedInPubSub"
        const val AUTH_LOG_TAG = "AuthQuickStart"
        const val CALLBACK_SCHEME = "com.example.signedinpubsub"
        const val AWS_IOT_ENDPOINT = "<your-iot-endpoint>"
        const val AWS_IOT_POLICY = "testpolicy"
        const val AWS_COGNITO_USER_POOL_ID = "cognito-idp.<your-region>.amazonaws.com/<your-user-pool-id>"
        const val AWS_COGNITO_POOL_ID = "<your-identify-pool-id>"
        val REGION = Regions.AP_NORTHEAST_1 // Change to your region
    }
}