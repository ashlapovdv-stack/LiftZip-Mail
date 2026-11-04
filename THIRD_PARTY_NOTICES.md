# Зависимости

Исходный код приложения написан для проекта Lift-Zip. Библиотеки не изменялись. Их лицензии сохраняются за авторами.

- Kotlin и kotlinx.coroutines — JetBrains, Apache License 2.0: https://github.com/JetBrains/kotlin
- AndroidX, Jetpack Compose, Material 3, Lifecycle и WorkManager — Android Open Source Project, Apache License 2.0: https://android.googlesource.com/platform/frameworks/support/
- Eclipse Angus Mail / Jakarta Mail — Eclipse Foundation, EPL 2.0 с альтернативными условиями GPL-2.0 with Classpath Exception: https://eclipse-ee4j.github.io/angus-mail/LICENSE
- Eclipse Angus Activation / Jakarta Activation — Eclipse Foundation, условия проекта: https://github.com/eclipse-ee4j/angus-activation
- Google Play services Auth / Google Identity Services — Google, условия Google APIs: https://developers.google.com/terms и https://developers.google.com/android/guides/setup
- JUnit 4 (только модульные тесты) — Eclipse Public License 1.0: https://junit.org/junit4/license.html
- Gradle Wrapper — Gradle, Apache License 2.0: https://github.com/gradle/gradle/blob/v8.13.0/LICENSE

Точные транзитивные зависимости фиксируются в `gradle.lockfile` при запуске фиксации зависимостей. Настоящий файл не заменяет лицензии и уведомления, поставляемые с каждой библиотекой.
