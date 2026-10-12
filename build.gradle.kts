// AGP 9.4.1, the latest, still brings these in at versions with published
// security advisories. They run only on the build machine; none of them
// reach the APK. Raise them on the build classpath rather than wait for a
// newer AGP. The versions live in the version catalog (buildscript-*) so
// Dependabot can see them and open its security pull requests against them.
buildscript {
    dependencies {
        constraints {
            classpath(libs.buildscript.bcprov)
            classpath(libs.buildscript.bcpkix)
            classpath(libs.buildscript.bcutil)
            classpath(libs.buildscript.jose4j)
            classpath(libs.buildscript.jdom2)
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
