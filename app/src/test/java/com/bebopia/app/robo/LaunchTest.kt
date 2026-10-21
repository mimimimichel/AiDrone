package com.bebopia.app.robo

import androidx.test.core.app.ActivityScenario
import com.bebopia.app.MainActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test
    fun launches() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            org.robolectric.shadows.ShadowLooper.idleMainLooper(2, java.util.concurrent.TimeUnit.SECONDS)
            s.onActivity { }
        }
    }
}
