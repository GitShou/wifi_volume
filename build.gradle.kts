// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

providers.gradleProperty("codexRootBuildDir").orNull?.let { buildDirOverride ->
    layout.buildDirectory.set(file(buildDirOverride))
}
