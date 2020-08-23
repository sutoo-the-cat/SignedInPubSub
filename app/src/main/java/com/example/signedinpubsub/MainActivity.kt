package com.example.signedinpubsub

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.amazonaws.regions.Region
import com.amazonaws.services.cognitoidentity.AmazonCognitoIdentityClient
import com.amazonaws.services.cognitoidentity.model.GetIdRequest
import com.amazonaws.services.iot.AWSIotClient
import com.amazonaws.services.iot.model.*
import com.amplifyframework.auth.AuthSession
import com.amplifyframework.auth.cognito.AWSCognitoAuthSession
import com.amplifyframework.auth.result.AuthSessionResult
import com.amplifyframework.core.Amplify
import com.auth0.android.jwt.DecodeException
import com.auth0.android.jwt.JWT
import java.io.UnsupportedEncodingException
import kotlin.collections.HashMap


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ユーザ認証の有効期限切れまたは未認証の場合はWebブラウザを開いて認証
        Amplify.Auth.signInWithWebUI(
            this,
            { result ->
                // サインイン成功
                Log.i(Constants.AUTH_LOG_TAG, result.toString())
                fetchSession()
            },
            { error ->
                // サインイン失敗
                Log.i(Constants.AUTH_LOG_TAG, error.toString())
            }
        )
    }

    // Webブラウザで認証した後戻ってきた時の処理
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)

        if(intent?.scheme != null && Constants.CALLBACK_SCHEME == intent.scheme) {
            Amplify.Auth.handleWebUISignInResponse(intent)
        }
    }


    // セッションを取得
    private fun fetchSession() {
        Amplify.Auth.fetchAuthSession(
            { session ->
                // ユーザ名とユーザIDトークンのペアを取得
                val pair = getUserNameAndIdToken(session)

                if(pair != null){
                    val thingName = pair.first
                    val idToken = pair.second

                    // 認証情報プロバイダの初期化
                    val credentialsProvider = CognitoCachingCredentialsProvider(
                        applicationContext,
                        Constants.AWS_COGNITO_POOL_ID,
                        Constants.REGION
                    )

                    // 認証情報プロバイダにユーザプールIDと認証済みユーザをマッピング
                    val logins: MutableMap<String, String> = HashMap()
                    logins[Constants.AWS_COGNITO_USER_POOL_ID] = idToken
                    credentialsProvider.logins = logins

                    Thread {
                        // AWS IoTクライアントの作成
                        val client = AWSIotClient(credentialsProvider)
                        client.setRegion(Region.getRegion(Constants.REGION))

                        // モノが作成されているか確認
                        val listThingsReq = ListThingsRequest()
                        val listThings = client.listThings(listThingsReq)
                        var hasAdded = false
                        for (i in listThings.things) {
                            if (i.thingName == thingName) {
                                hasAdded = true
                            }
                        }

                        // モノが作成されていない場合はユーザ名で作成する
                        if (!hasAdded) {
                            try{
                                val thingReq = CreateThingRequest()
                                thingReq.thingName = thingName
                                client.createThing(thingReq)
                            }catch(error: Exception){
                                Log.e(Constants.LOG_TAG, "Could not create the thing.", error)
                            }
                        }

                        // Cognito IDの取得
                        val getIdReq = GetIdRequest()
                        getIdReq.logins = credentialsProvider.logins
                        getIdReq.identityPoolId = Constants.AWS_COGNITO_POOL_ID
                        val cognitoIdentity = AmazonCognitoIdentityClient(credentialsProvider)
                        cognitoIdentity.setRegion(Region.getRegion(Constants.REGION))
                        val getIdRes = cognitoIdentity.getId(getIdReq)

                        // プリンシパルがモノに割り当てられているか確認
                        val listPrincipalThingsReq = ListPrincipalThingsRequest()
                        listPrincipalThingsReq.principal = getIdRes.identityId
                        val listPrincipalThings = client.listPrincipalThings(listPrincipalThingsReq)
                        var hasAttached = false
                        for (i in listPrincipalThings.things) {
                            if (i == thingName) {
                                hasAttached = true
                            }
                        }

                        // プリンシパルが割り当てられていない場合はモノにプリンシパルを割り当てる
                        if (!hasAttached){
                            try{
                                val policyReq = AttachPolicyRequest()
                                policyReq.policyName = Constants.AWS_IOT_POLICY // AWS IoTにアクセスするためのポリシー
                                policyReq.target = getIdRes.identityId // Cognito ID
                                client.attachPolicy(policyReq) // ポリシーをCognito IDに割り当てる

                                val principalReq = AttachThingPrincipalRequest()
                                principalReq.principal = getIdRes.identityId // プリンシパル（Cognito ID）
                                principalReq.thingName = thingName // モノの名前
                                client.attachThingPrincipal(principalReq) // プリンシパルをモノに割り当てる
                            }catch(error: Exception){
                                Log.e(Constants.LOG_TAG, "Could not attach the principal to the thing.", error)
                            }
                        }

                        // MQTTクライアントを作成
                        val mqttManager = AWSIotMqttManager(thingName, Constants.AWS_IOT_ENDPOINT)
                        mqttManager.keepAlive = 10

                        val buttonConnect: Button = findViewById(R.id.buttonConnect)
                        val buttonDisconnect: Button = findViewById(R.id.buttonDisconnect)
                        val buttonSubscribe: Button = findViewById(R.id.buttonSubscribe)
                        val buttonPublish: Button = findViewById(R.id.buttonPublish)
                        val textViewStatus: TextView = findViewById(R.id.textViewStatus)
                        val textViewMessage: TextView = findViewById(R.id.textViewMessage)

                        runOnUiThread {
                            buttonConnect.isEnabled = true // 接続可能になった時、Connectボタンを有効にする

                            // Connectボタンがクリックされた時の処理
                            buttonConnect.setOnClickListener {
                                buttonConnect.isEnabled = false
                                try {
                                    mqttManager.connect(credentialsProvider) { status, throwable ->
                                        Log.d(Constants.LOG_TAG, "Status = $status")

                                        runOnUiThread {
                                            when (status) {
                                                AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connecting -> {
                                                    textViewStatus.text =
                                                        getString(R.string.text_connecting)
                                                }
                                                AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                                                    textViewStatus.text =
                                                        getString(R.string.text_connected)
                                                    buttonDisconnect.isEnabled = true
                                                    buttonPublish.isEnabled = true
                                                    buttonSubscribe.isEnabled = true
                                                }
                                                AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting -> {
                                                    if (throwable != null) {
                                                        Log.e(Constants.LOG_TAG, "Connection error.", throwable)
                                                    }
                                                    textViewStatus.text =
                                                        getString(R.string.text_reconnecting)
                                                }
                                                AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                                                    if (throwable != null) {
                                                        Log.e(Constants.LOG_TAG, "Connection error.", throwable)
                                                    }
                                                    textViewStatus.text =
                                                        getString(R.string.text_disconnected)
                                                    buttonConnect.isEnabled = true
                                                }
                                                else -> {
                                                    textViewStatus.text =
                                                        getString(R.string.text_disconnected)
                                                    buttonConnect.isEnabled = true
                                                }
                                            }
                                        }
                                    }
                                } catch (error: Exception) {
                                    Log.e(Constants.LOG_TAG, "Subscription error.", error)
                                }
                            }

                            // Disconnectボタンがクリックされた時の処理
                            buttonDisconnect.setOnClickListener {
                                buttonDisconnect.isEnabled = false
                                buttonPublish.isEnabled = false
                                buttonSubscribe.isEnabled = false
                                buttonConnect.isEnabled = true
                                try {
                                    mqttManager.disconnect()
                                } catch (error: Exception) {
                                    Log.e(Constants.LOG_TAG, "Disconnect error.", error)
                                }
                            }

                            // Publishボタンがクリックされた時の処理
                            buttonPublish.setOnClickListener {
                                try {
                                    mqttManager.publishString("{\"message\":\"Test.\"}", "$thingName/to", AWSIotMqttQos.QOS1)
                                } catch (error: Exception) {
                                    Log.e(Constants.LOG_TAG, "Publish error.", error)
                                }
                            }

                            // Subscribeボタンがクリックされた時の処理
                            buttonSubscribe.setOnClickListener {

                                buttonSubscribe.isEnabled = false

                                try {
                                    mqttManager.subscribeToTopic(
                                        "$thingName/from", AWSIotMqttQos.QOS1
                                    ) { topic, data ->
                                        runOnUiThread { // トピックにメッセージが発行された時のみ実行
                                            try {
                                                val message = String(data)
                                                textViewMessage.text = message

                                            } catch (error: UnsupportedEncodingException) {
                                                Log.e(Constants.LOG_TAG, "Message encoding error.", error)
                                            }
                                        }
                                    }
                                } catch (error: Exception) {
                                    Log.e(Constants.LOG_TAG, "Subscription error.", error)
                                }
                            }
                        }
                    }.start()
                }
            },
            { error -> Log.e(Constants.AUTH_LOG_TAG, error.toString()) }
        )
    }

    // ユーザ名とユーザIDトークンの取得
    private fun getUserNameAndIdToken(session: AuthSession): Pair<String, String>? {
        val cognitoAuthSession = session as AWSCognitoAuthSession

        // セッション取得
        when (cognitoAuthSession.identityId.type) {
            // セッション取得が成功した場合はユーザ名とユーザIDトークンのペアを返す
            AuthSessionResult.Type.SUCCESS -> {
                val tokens = cognitoAuthSession.userPoolTokens.value
                if(tokens != null){
                    val idToken = tokens.idToken // ユーザIDトークン
                    try{
                        val jwt = JWT(idToken)
                        val token = jwt.toString()
                        val name = jwt.getClaim("cognito:username").asString()
                        if(name != null) return Pair(name, token)
                    }catch(error: DecodeException){
                        Log.e(Constants.LOG_TAG, "Could not decode tokens.", error)
                    }
                }
            }

            // セッション取得に失敗した場合はnullを返す
            AuthSessionResult.Type.FAILURE -> {
                Log.i(Constants.AUTH_LOG_TAG, "IdentityId not present because: " + cognitoAuthSession.identityId.error.toString())
            }
        }

        return null
    }
}
