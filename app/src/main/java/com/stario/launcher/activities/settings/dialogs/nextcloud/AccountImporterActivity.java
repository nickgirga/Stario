/*
 * Copyright (C) 2025 Nicholas Girga
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.activities.settings.dialogs.nextcloud;

import android.app.Activity;
import android.content.SharedPreferences;

/**
 * Wrapper class for Activity that delegates getSharedPreferences to application context.
 * This is needed because AccountImporter calls getSharedPreferences() on the Activity,
 * but ThemedActivity throws an exception requiring use of application context.
 */
public class AccountImporterActivity extends Activity {
    private final Activity wrappedActivity;
    
    public AccountImporterActivity(Activity activity) {
        this.wrappedActivity = activity;
    }
    
    @Override
    public SharedPreferences getSharedPreferences(String name, int mode) {
        // Delegate to application context to avoid ThemedActivity exception
        return wrappedActivity.getApplicationContext().getSharedPreferences(name, mode);
    }
    
    // Delegate other methods that AccountImporter might use
    @Override
    public Object getSystemService(String name) {
        return wrappedActivity.getSystemService(name);
    }
    
    @Override
    public String getPackageName() {
        return wrappedActivity.getPackageName();
    }
}
