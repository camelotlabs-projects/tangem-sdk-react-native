package com.tangem.RNTangemSdk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcAdapter
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule.RCTDeviceEventEmitter

import com.tangem.TangemSdk
import com.tangem.Message
import com.tangem.crypto.hdWallet.DerivationPath
import com.tangem.common.card.EllipticCurve
import com.tangem.common.core.TangemSdkError
import com.tangem.common.core.Config
import com.tangem.common.json.MoshiJsonConverter
import com.tangem.common.services.secure.SecureStorage
import com.tangem.common.CompletionResult
import com.tangem.common.extensions.hexToBytes
import com.tangem.crypto.bip39.Wordlist
import com.tangem.operations.attestation.AttestationTask
import com.tangem.sdk.DefaultSessionViewDelegate
import com.tangem.sdk.extensions.getWordlist
import com.tangem.sdk.extensions.initAuthenticationManager
import com.tangem.sdk.extensions.initKeystoreManager
import com.tangem.sdk.extensions.initNfcManager
import com.tangem.sdk.extensions.localizedDescription
import com.tangem.sdk.nfc.AndroidNfcAvailabilityProvider
import com.tangem.sdk.nfc.NfcManager
import com.tangem.sdk.storage.create

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.lang.ref.WeakReference


class RNTangemSdkModule(private val reactContext: ReactApplicationContext) :
    NativeTangemSdkModuleSpec(reactContext), LifecycleEventListener {
    private lateinit var nfcManager: NfcManager
    private lateinit var sdk: TangemSdk
    private val handler = Handler(Looper.getMainLooper())
    private val converter = MoshiJsonConverter.INSTANCE

    private var sessionStarted = false

    override fun getName(): String {
        return REACT_CLASS
    }

    companion object {
        const val REACT_CLASS = "RNTangemSdk"
        lateinit var wActivity: WeakReference<Activity?>
    }

    private val lifecycleowner: LifecycleOwner by lazy {
        ((reactContext as ReactContext).currentActivity as AppCompatActivity)
    }

    private fun initSdk() {
        val activity = currentActivity
        wActivity = WeakReference(currentActivity)

        activity ?: let {
            return
        }

        val config = Config()
        val secureStorage = SecureStorage.create(reactContext)

        nfcManager = TangemSdk.initNfcManager(activity as FragmentActivity)
        val authenticationManager =
            TangemSdk.initAuthenticationManager(activity)
        val keystoreManager =
            TangemSdk.initKeystoreManager(authenticationManager, secureStorage)


        val viewDelegate = DefaultSessionViewDelegate(nfcManager, activity)
        viewDelegate.sdkConfig = config

        val nfcAvailabilityProvider = AndroidNfcAvailabilityProvider(activity)

        sdk = TangemSdk(
            reader = nfcManager.reader,
            viewDelegate = viewDelegate,
            nfcAvailabilityProvider = nfcAvailabilityProvider,
            secureStorage = secureStorage,
            wordlist = Wordlist.getWordlist(activity),
            authenticationManager = authenticationManager,
            keystoreManager = keystoreManager,
            config = config,
        )
    }

    override fun onHostResume() {
        if (::nfcManager.isInitialized) {
            // check if activity is destroyed
            val activity = wActivity.get()
            activity ?: let {
                return
            }
            // if activity destroyed initialize again
            if (activity.isDestroyed || activity.isFinishing) {
                activity.runOnUiThread {
                    initSdk()
                }
            } else {
                if (sessionStarted) {
                    nfcManager.onStart(lifecycleowner)
                }
            }
        }
    }

    override fun onHostPause() {
        if (::nfcManager.isInitialized) {
            val activity = wActivity.get()
            activity ?: let {
                return
            }
            if (sessionStarted && nfcManager.reader.listener != null) {
                nfcManager.onStop(lifecycleowner)
            }
        }
    }

    override fun onHostDestroy() {
        if (::nfcManager.isInitialized) {
            val activity = wActivity.get()
            activity ?: let {
                return
            }
            nfcManager.onDestroy(lifecycleowner)
        }
    }


    @ReactMethod
    override fun startSession(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                if (sessionStarted) {
                    // session already started
                    promise.reject("ALREADY_STARTED", "session is already started", null)
                    return@runOnUiThread
                }

                // check if SDK is already isInitialized if not initialize it
                if (!::sdk.isInitialized) {
                    initSdk()
                }

                // double check if sdk is initialized
                if (!::sdk.isInitialized) {
                    promise.reject("NOT_INITIALIZED", "sdk is not initialized", null)
                    return@runOnUiThread
                }

                // set any passed config
                val optionsParser = OptionsParser(options)
                val config = Config()

                // set attestationMode if provided
                val attestationMode = optionsParser.getAttestationMode()
                if (attestationMode != null) {
                    config.attestationMode = attestationMode
                }

                // Apply default derivation paths if provided.
                // Three input shapes are supported by getDefaultDerivationPaths():
                //   1. String          → single path on Secp256k1 (legacy / XRP-style behaviour)
                //   2. Array<String>   → multiple paths on Secp256k1
                //   3. Map             → multi-curve, multi-path mapping
                //                        e.g. { "ed25519_slip0010": ["m/44'/3030'/0'/0'/0'", ...] }
                //                        Required for chains that use non-secp256k1 derivation
                //                        (e.g. Hedera uses SLIP-0010 ED25519).
                val multiCurvePaths = optionsParser.getDefaultDerivationPaths()
                if (multiCurvePaths != null && multiCurvePaths.isNotEmpty()) {
                    config.defaultDerivationPaths = multiCurvePaths.toMutableMap()
                }
                // set the new config to the SDK
                sdk.config = config
                // start the nfc manager
                nfcManager.onStart(lifecycleowner)
                // set the flag
                sessionStarted = true

                promise.resolve(null)
            } catch (ex: Exception) {
                promise.reject(ex)
            }
        }
    }

    @ReactMethod
    override fun stopSession(promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                if (!sessionStarted) {
                    // session already stopped
                    promise.reject("ALREADY_STOPPED", "session is already stopped", null)
                    return@runOnUiThread
                }
                // if session is started
                // and nfcManager is initialized
                if (::nfcManager.isInitialized) {
                    // set the default config for the SDk
                    sdk.config = Config()
                    // stop nfcManager
                    nfcManager.onStop(lifecycleowner)
                    // set the flag
                    sessionStarted = false
                    promise.resolve(null)
                } else {
                    promise.reject("NOT_INITIALIZED", "nfcManager is not initialized", null)
                }
            } catch (ex: Exception) {
                promise.reject(ex)
            }
        }
    }


    @ReactMethod
    override fun scanCard(options: ReadableMap?, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.scanCard(
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun createWallet(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.createWallet(
                    curve = optionsParser.getCurve(),
                    cardId = optionsParser.getCardId(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun purgeWallet(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.purgeWallet(
                    walletPublicKey = optionsParser.getWalletPublicKey(),
                    cardId = optionsParser.getCardId(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun sign(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.sign(
                    hashes = optionsParser.getHashes(),
                    walletPublicKey = optionsParser.getWalletPublicKey(),
                    cardId = optionsParser.getCardId(),
                    derivationPath = optionsParser.getDerivationPath(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }


    @ReactMethod
    override fun setAccessCode(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.setAccessCode(
                    accessCode = optionsParser.getAccessCode(),
                    cardId = optionsParser.getCardId(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun setPasscode(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.setPasscode(
                    passcode = optionsParser.getPasscode(),
                    cardId = optionsParser.getCardId(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun resetUserCodes(options: ReadableMap, promise: Promise) {
        UiThreadUtil.runOnUiThread {
            try {
                val optionsParser = OptionsParser(options)
                sdk.resetUserCodes(
                    cardId = optionsParser.getCardId(),
                    initialMessage = optionsParser.getInitialMessage()
                ) { handleResult(it, promise) }
            } catch (ex: Exception) {
                handleException(ex, promise)
            }
        }
    }

    @ReactMethod
    override fun getNFCStatus(promise: Promise) {
        val nfcAdapter = NfcAdapter.getDefaultAdapter(reactContext)

        val payload = Arguments.createMap()

        if (nfcAdapter != null) {
            payload.putBoolean("support", true)
            if (nfcAdapter.isEnabled) {
                payload.putBoolean("enabled", true)
            } else {
                payload.putBoolean("enabled", false)
            }
        } else {
            payload.putBoolean("support", false)
            payload.putBoolean("enabled", false)
        }

        return promise.resolve(payload)
    }

    private fun sendEvent(event: String, payload: WritableMap) {
        if (reactContext.hasActiveReactInstance()) {
            reactContext.getJSModule(RCTDeviceEventEmitter::class.java).emit(event, payload)
        }
    }

    private val mReceiver: BroadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                val action = intent.action
                if (action == NfcAdapter.ACTION_ADAPTER_STATE_CHANGED) {
                    val state = intent.getIntExtra(
                        NfcAdapter.EXTRA_ADAPTER_STATE, NfcAdapter.STATE_OFF
                    )
                    val payload = Arguments.createMap()
                    when (state) {
                        NfcAdapter.STATE_OFF -> {
                            payload.putBoolean("enabled", false)
                            sendEvent("NFCStateChange", payload)
                        }

                        NfcAdapter.STATE_ON -> {
                            payload.putBoolean("enabled", true)
                            sendEvent("NFCStateChange", payload)
                        }
                    }
                }
            }
        }


    @Throws(JSONException::class)
    fun toWritableMap(jsonObject: JSONObject): WritableMap {
        val writableMap = Arguments.createMap()
        val iterator = jsonObject.keys()
        while (iterator.hasNext()) {
            val key = iterator.next() as String
            val value = jsonObject.get(key)
            if (value is Float || value is Double) {
                writableMap.putDouble(key, jsonObject.getDouble(key))
            } else if (value is Number) {
                writableMap.putInt(key, jsonObject.getInt(key))
            } else if (value is String) {
                writableMap.putString(key, jsonObject.getString(key))
            } else if (value is Boolean) {
                writableMap.putBoolean(key, jsonObject.getBoolean(key))
            } else if (value is JSONObject) {
                writableMap.putMap(key, toWritableMap(jsonObject.getJSONObject(key)))
            } else if (value is JSONArray) {
                writableMap.putArray(key, toWritableMap(jsonObject.getJSONArray(key)))
            } else if (value === JSONObject.NULL) {
                writableMap.putNull(key)
            }
        }

        return writableMap
    }

    @Throws(JSONException::class)
    fun toWritableMap(jsonArray: JSONArray): WritableArray {
        val writableArray = Arguments.createArray()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.get(i)
            if (value is Float || value is Double) {
                writableArray.pushDouble(jsonArray.getDouble(i))
            } else if (value is Number) {
                writableArray.pushInt(jsonArray.getInt(i))
            } else if (value is String) {
                writableArray.pushString(jsonArray.getString(i))
            } else if (value is Boolean) {
                writableArray.pushBoolean(jsonArray.getBoolean(i))
            } else if (value is JSONObject) {
                writableArray.pushMap(toWritableMap(jsonArray.getJSONObject(i)))
            } else if (value is JSONArray) {
                writableArray.pushArray(toWritableMap(jsonArray.getJSONArray(i)))
            } else if (value === JSONObject.NULL) {
                writableArray.pushNull()
            }
        }
        return writableArray
    }

    private fun normalizeResponse(resp: Any?): WritableMap {
        val jsonObject = when (resp) {
            is String -> JSONObject(resp)
            else -> JSONObject(converter.toJson(resp))
        }
        return toWritableMap(jsonObject)
    }


    private fun handleResult(completionResult: CompletionResult<*>, promise: Promise) {
        when (completionResult) {
            is CompletionResult.Success -> {
                handler.post { promise.resolve(normalizeResponse(completionResult.data)) }
            }

            is CompletionResult.Failure -> {
                val error = completionResult.error
                val errorMessage = if (error is TangemSdkError) {
                    val activity = wActivity.get()
                    if (activity == null) error.customMessage else error.localizedDescription(
                        activity
                    )
                } else {
                    error.customMessage
                }
                handler.post {
                    promise.reject("${error.code}", errorMessage, null)
                }
            }
        }
    }

    private fun handleException(ex: Exception, promise: Promise) {
        handler.post {
            val code = 9999
            val localizedDescription: String = ex.toString()
            promise.reject("$code", localizedDescription, null)
        }
    }

    init {
        reactContext.addLifecycleEventListener(this)
        val filter = IntentFilter(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
        reactContext.registerReceiver(mReceiver, filter)
    }
}


class RequiredArgumentException(arg: String) : Exception(arg)


class OptionsParser(private val options: ReadableMap?) {
    fun getInitialMessage(): Message? {
        if (!options?.hasKey("initialMessage")!!) return null

        if (options.getMap("initialMessage") !is ReadableMap) {
            return null
        }
        val message = options.getMap("initialMessage") as ReadableMap

        val header = if (message.hasKey("header")) message.getString("header") else ""
        val body = if (message.hasKey("body")) message.getString("body") else ""
        return Message(
            header,
            body
        )
    }

    fun getWalletPublicKey(): ByteArray {
        val walletPublicKey = options?.getString("walletPublicKey")
        if (walletPublicKey.isNullOrEmpty()) {
            throw RequiredArgumentException("walletPublicKey is required")
        }
        return walletPublicKey.hexToBytes()
    }

    fun getCardId(): String {
        val cardId = options?.getString("cardId")
        if (cardId.isNullOrEmpty()) {
            throw RequiredArgumentException("cardId is required")
        }
        return cardId
    }

    fun getAccessCode(): String? {
        val accessCode = options?.getString("accessCode")
        if (accessCode.isNullOrEmpty()) {
            return null
        }
        return accessCode
    }

    fun getPasscode(): String? {
        val passcode = options?.getString("passcode")
        if (passcode.isNullOrEmpty()) {
            return null
        }
        return passcode
    }


    fun getCurve(): EllipticCurve {
        return when (options?.getString("curve")) {
            "ed25519" -> EllipticCurve.Ed25519
            "secp256k1" -> EllipticCurve.Secp256k1
            "secp256r1" -> EllipticCurve.Secp256r1
            else -> {
                EllipticCurve.Secp256k1
            }
        }
    }

    fun getHashes(): Array<ByteArray> {
        val hashes = options?.getArray("hashes")
        if (hashes === null || hashes.size() === 0) {
            throw RequiredArgumentException("hashes is required")
        }
        return hashes.toArrayList().map { it.toString().hexToBytes() }.toTypedArray()
    }


    fun getDerivationPath(): DerivationPath? {
        val derivationPath = options?.getString("derivationPath")
        if (derivationPath.isNullOrEmpty()) {
            return null
        }
        return DerivationPath(rawPath = derivationPath)
    }


    fun getAttestationMode(): AttestationTask.Mode? {
        return when (options?.getString("attestationMode")) {
            "offline" -> AttestationTask.Mode.Offline
            "normal" -> AttestationTask.Mode.Normal
            "full" -> AttestationTask.Mode.Full
            else -> {
                null
            }
        }
    }

    fun getDefaultDerivationPath(): DerivationPath? {
        // Legacy single-path accessor (kept for backwards compatibility with callers
        // that only need secp256k1; the multi-curve path goes via getDefaultDerivationPaths).
        val defaultPath = options?.getString("defaultDerivationPaths")
        if (defaultPath.isNullOrEmpty()) {
            return null
        }
        return DerivationPath(rawPath = defaultPath)
    }

    /**
     * Resolve a JS-side curve name (e.g. "ed25519_slip0010", "secp256k1") to the SDK enum.
     *
     * Strategy:
     *   1. Explicit hardcoded mapping for known Tangem-Android SDK enum names (PascalCase).
     *      This is the **primary, safest** path — reflection-free, deterministic.
     *   2. Heuristic name-variant probing (snake_case, PascalCase, lowercase, etc.).
     *      Fallback for SDK versions where enum names diverge from our hardcoded list.
     *   3. Brute-force scan via `EllipticCurve.values()` matching by name/toString().
     *
     * Returns null if no match — caller skips that curve silently.
     */
    private fun resolveCurve(jsName: String): EllipticCurve? {
        val normalized = jsName.lowercase()

        // Strategy 1: explicit known mappings (deterministic, no reflection surprises).
        // Maps JS rawValue (lowercase snake_case, matches iOS EllipticCurve.swift) to
        // the Tangem-android Kotlin enum name (PascalCase, observed in v3.9.2 source).
        val explicitKotlinName: String? = when (normalized) {
            "secp256k1" -> "Secp256k1"
            "secp256r1" -> "Secp256r1"
            "ed25519" -> "Ed25519"
            "ed25519_slip0010" -> "Ed25519Slip0010"
            "bip0340" -> "Bip0340"
            "bls12381_g2" -> "Bls12381G2"
            "bls12381_g2_aug" -> "Bls12381G2Aug"
            "bls12381_g2_pop" -> "Bls12381G2Pop"
            else -> null
        }
        if (explicitKotlinName != null) {
            runCatching { EllipticCurve.valueOf(explicitKotlinName) }.getOrNull()?.let { return it }
        }

        // Strategy 2: heuristic name-variant probing (covers SDK refactors where the
        // enum naming convention diverges from our hardcoded map above).
        val variants = mutableSetOf(
            jsName,
            jsName.lowercase(),
            jsName.uppercase(),
            // snake_case → PascalCase: "ed25519_slip0010" → "Ed25519Slip0010"
            jsName.split('_').joinToString("") { part ->
                part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            },
            // First-letter capitalised: "secp256k1" → "Secp256k1"
            jsName.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            // Strip underscores: "ed25519_slip0010" → "Ed25519slip0010"
            jsName.replace("_", "").replaceFirstChar {
                if (it.isLowerCase()) it.titlecase() else it.toString()
            }
        )
        for (variant in variants) {
            runCatching { EllipticCurve.valueOf(variant) }.getOrNull()?.let { return it }
        }

        // Strategy 3: brute-force scan all entries case-insensitively.
        val match = EllipticCurve.values().firstOrNull { entry ->
            entry.name.equals(jsName, ignoreCase = true) ||
                entry.toString().equals(jsName, ignoreCase = true) ||
                entry.name.replace("_", "").equals(jsName.replace("_", ""), ignoreCase = true)
        }
        if (match == null) {
            android.util.Log.w(
                "RNTangemSdk",
                "resolveCurve: no EllipticCurve match for JS curve name \"$jsName\""
            )
        }
        return match
    }

    /**
     * Multi-curve, multi-path derivation paths.
     * Accepts a ReadableMap with curve-name keys mapping to ReadableArrays of raw path strings:
     *   { "ed25519_slip0010": ["m/44'/3030'/0'/0'/0'", ...], "secp256k1": [...] }
     * Also accepts a single string (legacy: maps to Secp256k1) and a single array of strings
     * (assumed Secp256k1 for backwards compatibility).
     * Unknown curve names and unparseable paths are silently skipped, never throw.
     */
    fun getDefaultDerivationPaths(): Map<EllipticCurve, List<DerivationPath>>? {
        if (options == null || !options.hasKey("defaultDerivationPaths")) return null

        val type = options.getType("defaultDerivationPaths")

        // Shape 1: Map { curveName: [pathString, ...] }
        if (type == ReadableType.Map) {
            val dict = options.getMap("defaultDerivationPaths") ?: return null
            val result = mutableMapOf<EllipticCurve, List<DerivationPath>>()
            val iter = dict.keySetIterator()
            while (iter.hasNextKey()) {
                val curveName = iter.nextKey()
                val curve = resolveCurve(curveName) ?: continue
                if (dict.getType(curveName) != ReadableType.Array) continue
                val arr = dict.getArray(curveName) ?: continue
                val paths = mutableListOf<DerivationPath>()
                for (i in 0 until arr.size()) {
                    if (arr.getType(i) != ReadableType.String) continue
                    val raw = arr.getString(i) ?: continue
                    runCatching { DerivationPath(rawPath = raw) }.getOrNull()?.let { paths.add(it) }
                }
                if (paths.isNotEmpty()) {
                    result[curve] = paths
                }
            }
            return if (result.isEmpty()) null else result
        }

        // Shape 2: Array of path strings — assumed Secp256k1 (legacy)
        if (type == ReadableType.Array) {
            val arr = options.getArray("defaultDerivationPaths") ?: return null
            val paths = mutableListOf<DerivationPath>()
            for (i in 0 until arr.size()) {
                if (arr.getType(i) != ReadableType.String) continue
                val raw = arr.getString(i) ?: continue
                runCatching { DerivationPath(rawPath = raw) }.getOrNull()?.let { paths.add(it) }
            }
            return if (paths.isEmpty()) null else mapOf(EllipticCurve.Secp256k1 to paths)
        }

        // Shape 3: Single path string — assumed Secp256k1 (legacy v3.1.0 behaviour)
        if (type == ReadableType.String) {
            val raw = options.getString("defaultDerivationPaths") ?: return null
            if (raw.isEmpty()) return null
            return runCatching { DerivationPath(rawPath = raw) }
                .getOrNull()
                ?.let { mapOf(EllipticCurve.Secp256k1 to listOf(it)) }
        }

        return null
    }
}
