# Kotlin Test data Helper

[![Version](https://img.shields.io/jetbrains/plugin/v/17620.svg)](https://plugins.jetbrains.com/plugin/17620)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/17620.svg)](https://plugins.jetbrains.com/plugin/17620)

<!-- Plugin description -->

This plugin introduces some nice features to make working with compiler tests of [Kotlin](https://github.com/JetBrains/kotlin) project more cozy.

### Main features:

- Separated view for files related to opened testdata file
- Ability to run specific generated test, associated with opened testdata file
- Ability to run all tests associated with opened file

#### Previews
- Test Data editor
![TestData Editor](pic/testDataEditor.png)
  
- Split view for files related to opened tests
![Split view](pic/splitEditor.png)


#### Setup

Plugin creates enhanced editor for any `.kt` files which belong to directories listed in plugin settings (they are stored per project in `.idea/kotlinTestDataPluginTestDataPaths.xml` file)

![Settings](pic/settings.png)

The plugin assumes that if some test file named `someName.kt` then all files which start with `someName.` are considered related to this test, and it shows them in the left combobox for split mode.
The plugin can be configured to search for related files in other places (not just in the same directory as the `.kt` file).
This can be useful, for example, for displaying the generated code when working on Kotlin/JS tests. 

For detecting test methods for opened file plugin uses following conversion:
- if test file named `someName.kt` then test method should name `testSomeName` and marked as test (e.g. using `@Test` annotation from JUnit)
- test method should be annotated with `@org.jetbrains.kotlin.test.TestMetadata` annotation which stores filename in its argument (`@TestMetadata("someName.kt")`)
- class which contains test method also should me marked with `@TestMetadata` annotation, and path to directory with `someName.kt` file should be passed to its argument

Plugin searches for all methods in project which satisfy those conditions and creates buttons for running those test methods in right corner of toolbar. Also right panel has two additional buttons:
- _Reload_ button runs detection of test methods again. This can be useful if you add new test method after opening test data file
- _Run all_ button runs gradle command which executes all found test methods
<!-- Plugin description end -->

This plugin hosted in custom repository, so if you want to receive automatic updates please add this repository to _Settings_ -> _Plugins_ -> _Manage Plugin Repositories_

```
https://teamcity.jetbrains.com/guestAuth/app/rest/builds/buildType:(id:Kotlin_KotlinCompilerTestHelper_BuildPlugin),status:success,count:1/artifacts/content/updatePlugins.xml
``` 
