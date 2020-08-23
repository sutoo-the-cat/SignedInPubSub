package com.example.signedinpubsub

import android.app.Application
import android.util.Log
import com.amplifyframework.AmplifyException
import com.amplifyframework.auth.cognito.AWSCognitoAuthPlugin
import com.amplifyframework.core.Amplify

class Initialization: Application() {
    override fun onCreate() {
        super.onCreate()

        // Amplifyの初期化（アプリ起動時に1回だけ実施）
        try {
            Amplify.addPlugin(AWSCognitoAuthPlugin())
            Amplify.configure(applicationContext)
            Log.i(Constants.LOG_TAG, "Initialized Amplify")
        } catch (error: AmplifyException) {
            Log.e(Constants.LOG_TAG, "Could not initialize Amplify", error)
        }
    }
}