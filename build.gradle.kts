// AGP 9.4.1, the latest, still brings these in at versions with published
// security advisories (Dependabot alerts). They run only on the build
// machine; none of them reach the APK. Raise them on the build classpath
// rather than wait for a newer AGP. Bouncy Castle's three modules move
// together.
buildscript {
    dependencies {
        constraints {
            listOf("bcprov", "bcpkix", "bcutil").forEach { module ->
                classpath("org.bouncycastle:$module-jdk18on:1.86") { because("advisories fixed in 1.84/1.85") }
            }
            classpath("org.bitbucket.b_c:jose4j:0.9.7") { because("advisory fixed in 0.9.6") }
            classpath("org.jdom:jdom2:2.0.6.1") { because("advisory fixed in 2.0.6.1") }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
