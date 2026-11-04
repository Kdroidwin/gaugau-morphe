package jp.futabanet.gaugau.patches

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.w3c.dom.Element

private val gaugau146 = Compatibility(
    name = "がうがう",
    packageName = "jp.futabanet.gaugau.app",
    apkFileType = ApkFileType.APK,
    targets = listOf(AppTarget(version = "1.46.0")),
)

private val gaugauManifestPatch = resourcePatch(
    name = "GauGau privacy manifest settings",
    description = "Disables Firebase collection metadata and removes advertising attribution permissions.",
    default = false,
) {
    compatibleWith(gaugau146)
    execute {
        document("AndroidManifest.xml").use { manifest ->
            val root = manifest.documentElement
            val android = "http://schemas.android.com/apk/res/android"
            val removedPermissions = setOf(
                "com.google.android.gms.permission.AD_ID",
                "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
                "android.permission.ACCESS_ADSERVICES_AD_ID",
                "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
            )
            val permissionNodes = (0 until root.childNodes.length)
                .mapNotNull { root.childNodes.item(it) as? Element }
                .filter { it.tagName == "uses-permission" && it.getAttributeNS(android, "name") in removedPermissions }
            permissionNodes.forEach(root::removeChild)

            val application = (0 until root.childNodes.length)
                .mapNotNull { root.childNodes.item(it) as? Element }
                .single { it.tagName == "application" }
            val appFlyerComponents = listOf("provider", "receiver", "service").flatMap { tag ->
                val nodes = application.getElementsByTagName(tag)
                (0 until nodes.length)
                    .mapNotNull { nodes.item(it) as? Element }
                    .filter { it.getAttributeNS(android, "name").contains("appsflyer", ignoreCase = true) }
            }
            appFlyerComponents.forEach { it.parentNode.removeChild(it) }

            val meta = mapOf(
                "firebase_analytics_collection_enabled" to "false",
                "firebase_crashlytics_collection_enabled" to "false",
                "firebase_performance_collection_deactivated" to "true",
                "firebase_messaging_auto_init_enabled" to "false",
                "firebase_sessions_enabled" to "false",
                "google_analytics_adid_collection_enabled" to "false",
            )
            meta.forEach { (name, value) ->
                val nodes = application.getElementsByTagName("meta-data")
                val existing = (0 until nodes.length)
                    .mapNotNull { nodes.item(it) as? Element }
                    .firstOrNull { it.getAttributeNS(android, "name") == name }
                val element = existing ?: manifest.createElement("meta-data").also(application::appendChild)
                element.setAttributeNS(android, "android:name", name)
                element.setAttributeNS(android, "android:value", value)
            }
        }
    }
}

private fun MutableMethod.returnEarly(value: Any? = null) {
    checkNotNull(implementation) { "Cannot add an early return to abstract method $name." }
    val instructions = when (returnType) {
        "V" -> {
            require(value == null) { "Void method $name cannot return a value." }
            "return-void"
        }
        "Z" -> {
            val booleanValue = value as? Boolean ?: false
            "const/4 v0, ${if (booleanValue) "0x1" else "0x0"}\nreturn v0"
        }
        "Ljava/lang/String;" -> {
            val stringValue = value as? String
            if (stringValue == null) {
                "const/4 v0, 0x0\nreturn-object v0"
            } else {
                val escaped = stringValue
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t")
                "const-string v0, \"$escaped\"\nreturn-object v0"
            }
        }
        else -> error("Unsupported early return type $returnType in $name.")
    }
    addInstructions(0, instructions)
}

@Suppress("unused")
val gaugauPrivacyPatch = bytecodePatch(
    name = "GauGau privacy controls and favorites transfer",
    description = "Adds privacy controls and favorites transfer, including support for devices without Google services.",
    default = true,
) {
    compatibleWith(gaugau146)
    dependsOn(gaugauManifestPatch)
    extendWith("extensions/extension.mpe")

    execute {
        // PairIP startup still loads its VM; only the signature and Play licensing checks are skipped.
        mutableClassDefBy("Lcom/pairip/SignatureCheck;")
            .methods.single { it.name == "verifyIntegrity" && it.parameterTypes == listOf("Landroid/content/Context;") }
            .returnEarly()
        mutableClassDefBy("Lcom/pairip/licensecheck/LicenseClient;")
            .methods.single { it.name == "checkLicense" && it.parameterTypes == listOf("Landroid/content/Context;") }
            .returnEarly()

        // GauGau runs an asynchronous Google Play Services gate during Application startup.
        // Report its success so the app does not open a Play Services recovery screen.
        mutableClassDefBy("Lbb/b;")
            .methods.single {
                it.name == "doInBackground" &&
                    it.parameterTypes == listOf("[Ljava/lang/Object;") &&
                    it.returnType == "Ljava/lang/Object;"
            }
            .addInstructions(
                0,
                """
                    const/4 v0, 0x0
                    invoke-static { v0 }, Ljava/lang/Integer;->valueOf(I)Ljava/lang/Integer;
                    move-result-object v0
                    return-object v0
                """,
            )

        // The app hashes this value before using it as its server-side device token.
        val appOnCreate = mutableClassDefBy("Ljp/co/link_u/gaugau/App;")
            .methods.single { it.name == "onCreate" && it.parameterTypes.isEmpty() }
        val androidIdCalls = appOnCreate.implementation!!.instructions.indices.filter { index ->
            val reference = (appOnCreate.implementation!!.instructions[index] as? ReferenceInstruction)
                ?.reference as? MethodReference
            reference?.definingClass == "Landroid/provider/Settings\$Secure;" &&
                reference.name == "getString" &&
                reference.parameterTypes == listOf("Landroid/content/ContentResolver;", "Ljava/lang/String;")
        }
        check(androidIdCalls.size == 1) { "Expected one Android ID read in GauGau 1.46.0, found ${androidIdCalls.size}." }
        appOnCreate.replaceInstruction(
            androidIdCalls.single(),
            "invoke-static/range { p0 .. p0 }, Ljp/futabanet/gaugau/patch/DeviceId;->getId(Landroid/content/Context;)Ljava/lang/String;",
        )

        // Stop AppsFlyer collection at its concrete SDK implementation.
        val appsFlyer = mutableClassDefBy("Lcom/appsflyer/internal/AFa1tSDK;")
        val blockedStarts = appsFlyer.methods.filter { method ->
            method.name == "start" && method.returnType == "V" &&
                method.parameterTypes.firstOrNull() == "Landroid/content/Context;"
        }
        check(blockedStarts.isNotEmpty()) { "AppsFlyer start entry point was not found." }
        blockedStarts.forEach { it.returnEarly() }
        appsFlyer.methods.filter { method ->
            method.name == "logEvent" && method.returnType == "V"
        }.forEach { it.returnEarly() }
        appsFlyer.methods.single { it.name == "setCustomerUserId" && it.returnType == "V" }
            .returnEarly()
        appsFlyer.methods.single { method ->
            method.name == "getAppsFlyerUID" && method.returnType == "Ljava/lang/String;"
        }.returnEarly("")
        appsFlyer.methods.filter { method ->
            method.name == "updateServerUninstallToken" && method.returnType == "V"
        }.forEach { it.returnEarly() }

        // Disable crash identity enrichment while preserving Firebase objects expected by app code.
        mutableClassDefBy("Lcom/google/firebase/crashlytics/FirebaseCrashlytics;")
            .methods.single { it.name == "setUserId" && it.parameterTypes == listOf("Ljava/lang/String;") }
            .returnEarly()

        // Tapjoy is an optional offerwall/reward SDK. Refuse its network bootstrap.
        val tapjoy = mutableClassDefBy("Lcom/tapjoy/Tapjoy;")
        tapjoy.methods.filter { it.name == "connect" && it.returnType == "Z" }
            .forEach { it.returnEarly(false) }

        // MainActivity's startup coroutine only gathers advertising, FCM, and AppsFlyer IDs.
        // Skipping it also avoids the Google Play Services calls on GMS-free devices.
        mutableClassDefBy("Ljp/co/link_u/gaugau/feature/MainActivity;")
            .methods.single { it.name == "z" && it.returnType == "Ljava/lang/Object;" }
            .addInstructions(
                0,
                """
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                """,
            )

        // Block app-side analytics requests while leaving manga, account, and purchase APIs intact.
        val analyticsMethods = setOf(
            "C", "F", "G", "H", "K", "M", "N", "V", "W", "d0", "f0", "j0",
            "l", "m", "m0", "n", "q0", "u0", "w", "w0", "x", "y", "y0", "z",
        )
        val analyticsWrappers = mutableClassDefBy("Lkg/z1;").methods.filter { method ->
            method.returnType == "Ljava/lang/Object;" &&
                method.implementation?.instructions?.any { instruction ->
                    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                    reference?.definingClass == "Lkg/c;" && reference.name in analyticsMethods
                } == true
        }
        check(analyticsWrappers.isNotEmpty()) { "GauGau analytics API wrappers were not found." }
        analyticsWrappers.forEach { method ->
            method.addInstructions(
                0,
                """
                    sget-object v0, Lkotlin/Unit;->a:Lkotlin/Unit;
                    return-object v0
                """,
            )
        }

        // Piano Analytics persists and transmits queued event batches through this worker.
        mutableClassDefBy("Lwf/q;")
            .methods.single { it.name == "run" && it.parameterTypes.isEmpty() }
            .returnEarly()
        mutableClassDefBy("Ljp/co/link_u/gaugau/feature/MainActivity;")
            .methods.single { it.name == "A" && it.returnType == "V" }
            .returnEarly()
        mutableClassDefBy("Loh/n;")
            .methods.single { it.name == "c" && it.returnType == "V" && it.parameterTypes.isEmpty() }
            .returnEarly()

        // Keep the activity available to the patch-added settings rows.
        mutableClassDefBy("Ljp/co/link_u/gaugau/feature/MainActivity;")
            .methods.single { it.name == "onResume" && it.parameterTypes.isEmpty() }
            .addInstructions(
                0,
                "invoke-static/range { p0 .. p0 }, Ljp/futabanet/gaugau/patch/GaugauActivityBridge;->setCurrentActivity(Landroid/app/Activity;)V",
            )

        // Add app-local ID and favorites transfer rows to GauGau's existing Compose settings page.
        val settings = mutableClassDefBy("Lej/a;")
            .methods.single {
                it.name == "d" &&
                    it.parameterTypes == listOf("Lkotlin/jvm/functions/Function1;", "Lf1/g0;", "I")
            }
        val settingsSectionClose = settings.implementation!!.instructions.indices.single { index ->
            val reference = (settings.implementation!!.instructions[index] as? ReferenceInstruction)
                ?.reference as? MethodReference
            reference?.definingClass == "Lf1/g0;" &&
                reference.name == "q" &&
                reference.parameterTypes == listOf("Z")
        }
        settings.addInstructions(
            settingsSectionClose,
            "invoke-static/range { p1 .. p1 }, Ljp/futabanet/gaugau/patch/GaugauSettingsRows;->addRows(Ljava/lang/Object;)V",
        )

        // Hook after GauGau's parent callback has dispatched the SAF result.
        val activityResult = mutableClassDefBy("Lk/j;")
            .methods.single {
                it.name == "onActivityResult" &&
                    it.parameterTypes == listOf("I", "I", "Landroid/content/Intent;")
            }
        val parentResultCallbacks = activityResult.implementation!!.instructions.indices.filter { index ->
            val reference = (activityResult.implementation!!.instructions[index] as? ReferenceInstruction)
                ?.reference as? MethodReference
            reference?.definingClass == "Ld/m;" &&
                reference.name == "onActivityResult" &&
                reference.parameterTypes == listOf("I", "I", "Landroid/content/Intent;")
        }
        check(parentResultCallbacks.size == 1) {
            "Expected one parent onActivityResult call in GauGau 1.46.0, found ${parentResultCallbacks.size}."
        }
        activityResult.addInstructions(
            parentResultCallbacks.single() + 1,
            "invoke-static/range { p0 .. p3 }, Ljp/futabanet/gaugau/patch/FavoritesTransfer;->onActivityResult(Landroid/app/Activity;IILandroid/content/Intent;)V",
        )

    }
}
