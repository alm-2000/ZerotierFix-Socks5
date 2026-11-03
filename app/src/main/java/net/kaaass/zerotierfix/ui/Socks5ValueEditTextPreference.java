package net.kaaass.zerotierfix.ui;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceViewHolder;

import net.kaaass.zerotierfix.R;

/** Existing editable SOCKS5 setting with its saved value shown at the right of the row. */
public class Socks5ValueEditTextPreference extends EditTextPreference {
    public Socks5ValueEditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_socks5_value);
    }

    public void refreshDisplayedValue() {
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View widget = holder.findViewById(R.id.socks5_preference_value);
        if (widget instanceof TextView) {
            String value = getText();
            ((TextView) widget).setText(TextUtils.isEmpty(value)
                    ? getContext().getString(R.string.preferences_value_not_set) : value);
        }
    }
}
