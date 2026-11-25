// Fork only, see FORK.md. Used by the "Fork Release" workflow with `./gradlew -I <this file>`.
//
// Robolectric downloads the Android runtime of every SDK level the tests ask for while they run,
// from Maven Central by default. Those downloads fail every now and then on the GitHub runners,
// and the tests that needed them fail with an `initializationError`. Google's mirror of Maven
// Central serves the same files.
allprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        systemProperty("robolectric.dependency.repo.id", "maven-central-mirror")
    }
}
