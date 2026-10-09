plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    // JSON-RPC transports for every chain (shared with the app's OkHttp).
    implementation(libs.okhttp)
    // The outbound-proxy policy the transports consult (domain:pure Kotlin, no engine).
    implementation(project(":core:domain"))
    // EVM: secp256k1 signing, RLP, EIP-155/1559 transactions, EIP-712 typed
    // data, V3 keystore files, BIP39 mnemonics, scrypt.
    implementation(libs.web3j.crypto)
    // ed25519 (Solana/Aptos/Sui), blake2b, sha3, ripemd160, EC point math for BIP32.
    implementation(libs.bouncycastle.provider)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // Sealed-circle envelopes can only be built by the protocol's writer, which the app
    // does not ship; the domain module exposes it as a test fixture for exactly this.
    testImplementation(testFixtures(project(":core:domain")))
}
