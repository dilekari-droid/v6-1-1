from pathlib import Path
import sys

android = Path(sys.argv[1]).resolve()
layout = android / 'app/src/main/res/layout/activity_scan_results.xml'
activity = android / 'app/src/main/java/tr/borsatakip/v5/ui/ScanResultsActivity.kt'


def replace_once(path: Path, before: str, after: str, label: str):
    text = path.read_text()
    count = text.count(before)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly 1 match, got {count}')
    path.write_text(text.replace(before, after, 1))

old_filter = '''        <HorizontalScrollView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="5dp"
            android:fillViewport="false"
            android:scrollbars="none">

            <LinearLayout
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:orientation="horizontal">

                <Button
                    android:id="@+id/scanAll"
                    style="@style/ScanFilterButton"
                    android:text="@string/auto_text_ed61bdd83b" />

                <Button
                    android:id="@+id/scanLong"
                    style="@style/ScanFilterButton"
                    android:layout_marginStart="6dp"
                    android:text="@string/auto_text_0b718d8575" />

                <Button
                    android:id="@+id/scanShort"
                    style="@style/ScanFilterButton"
                    android:layout_marginStart="6dp"
                    android:text="@string/auto_text_338006fefa" />

                <Button
                    android:id="@+id/scan85"
                    style="@style/ScanFilterButton"
                    android:layout_marginStart="6dp"
                    android:text="@string/auto_text_cac0b8c5cf" />

                <Button
                    android:id="@+id/scanScore"
                    style="@style/ScanFilterButton"
                    android:layout_marginStart="6dp"
                    android:text="@string/auto_text_76ec87dddf" />
            </LinearLayout>
        </HorizontalScrollView>'''

button_common = '''
                    style="@style/ScanFilterButton"
                    android:layout_width="0dp"
                    android:layout_height="48dp"
                    android:layout_weight="1"
                    android:minWidth="0dp"
                    android:paddingStart="2dp"
                    android:paddingEnd="2dp"
                    android:autoSizeTextType="uniform"
                    android:autoSizeMinTextSize="7sp"
                    android:autoSizeMaxTextSize="11sp"
                    android:autoSizeStepGranularity="1sp"'''

new_filter = f'''        <LinearLayout
            android:id="@+id/scanResultFilters"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="5dp"
            android:orientation="horizontal"
            android:weightSum="5">

            <Button
                android:id="@+id/scanAll"{button_common}
                android:text="@string/auto_text_ed61bdd83b" />

            <Button
                android:id="@+id/scanLong"{button_common}
                android:layout_marginStart="2dp"
                android:text="@string/auto_text_0b718d8575" />

            <Button
                android:id="@+id/scanShort"{button_common}
                android:layout_marginStart="2dp"
                android:text="@string/auto_text_338006fefa" />

            <Button
                android:id="@+id/scan85"{button_common}
                android:layout_marginStart="2dp"
                android:text="@string/auto_text_cac0b8c5cf" />

            <Button
                android:id="@+id/scanScore"{button_common}
                android:layout_marginStart="2dp"
                android:text="@string/auto_text_76ec87dddf" />
        </LinearLayout>'''

replace_once(layout, old_filter, new_filter, 'responsive scan result filter row')

old_color = '''            button.setTextColor(getColor(when (target) {
                Mode.LONG -> R.color.green
                Mode.SHORT -> R.color.red
                else -> R.color.text_primary
            }))'''
new_color = '''            button.setTextColor(getColor(when {
                selected -> R.color.white
                target == Mode.LONG -> R.color.green
                target == Mode.SHORT -> R.color.red
                else -> R.color.text_primary
            }))'''
replace_once(activity, old_color, new_color, 'selected filter text contrast')

# Fail closed: all five controls must remain exactly once and the filter row must no longer scroll.
text = layout.read_text()
for marker in ('@+id/scanAll', '@+id/scanLong', '@+id/scanShort', '@+id/scan85', '@+id/scanScore'):
    if text.count(marker) != 1:
        raise SystemExit(f'{marker}: expected exactly 1 occurrence, got {text.count(marker)}')
if '@+id/scanResultFilters' not in text or 'android:weightSum="5"' not in text:
    raise SystemExit('responsive filter row marker missing')
idx = text.index('@+id/scanResultFilters')
window = text[max(0, idx - 300):idx + 5000]
if '<HorizontalScrollView' in window:
    raise SystemExit('filter row is still inside a HorizontalScrollView')
