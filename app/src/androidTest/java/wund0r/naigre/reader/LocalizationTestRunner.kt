// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.test.runner.AndroidJUnitRunner

/** Test-only resource overrides; never change system settings or add a shipping language picker. */
class LocalizationTestRunner : AndroidJUnitRunner() {
    companion object {
        @Volatile var activityConfiguration: Configuration? = null
    }

    override fun newActivity(loader: ClassLoader, name: String, intent: Intent): Activity =
        super.newActivity(loader, name, intent).also { activity ->
            if (activity is MainActivity) {
                activityConfiguration?.let { activity.applyOverrideConfiguration(Configuration(it)) }
            }
        }

    override fun callActivityOnCreate(activity: Activity, state: Bundle?) {
        super.callActivityOnCreate(activity, state)
        if (activity is MainActivity) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
