#!/usr/bin/env python3
"""Generates res/values/themes_custom.xml: one dark and one light liquid-glass style per hue.

Android themes are static resources, so a user-picked colour cannot be written into a theme at
runtime. Instead ThemeManager picks the style whose hue is closest to the chosen colour, so cards,
dock, chips and accents all take on that colour. Re-run this script if the palette needs changing:

    python3 scripts/gen_custom_theme_styles.py > app/src/main/res/values/themes_custom.xml
"""
import colorsys

HUES = 24  # one style per 15 degrees


def hexa(h, s, l, a=255):
    r, g, b = colorsys.hls_to_rgb(h / 360.0, l, s)
    return "#%02X%02X%02X%02X" % (a, round(r * 255), round(g * 255), round(b * 255))


def style(mode, idx):
    h = idx * (360 // HUES)
    dark = mode == "Dark"
    name = "Theme.JarvisAssistant.Custom%s_H%02d" % (mode, idx)
    parent = "Theme.Material3.%s.NoActionBar" % ("Dark" if dark else "Light")
    if dark:
        accent = hexa(h, .85, .64)
        a2 = hexa((h + 28) % 360, .85, .68)
        a3 = hexa((h - 28) % 360, .85, .62)
        v = {
            "colorPrimary": accent, "colorOnPrimary": "#FFFFFF",
            "colorSurface": hexa(h, .40, .13, 0xCC), "colorOnSurface": "#F8FAFC",
            "themeSurface": hexa(h, .40, .13, 0x66),
            "themeCardBg": hexa(h, .40, .14, 0x66),
            "themeCardBorder": hexa(h, .70, .60, 0x4D),
            "themeCardGlassStart": hexa(h, .40, .20, 0x66),
            "themeCardGlassEnd": hexa(h, .40, .10, 0x33),
            "themeDockBg": hexa(h, .45, .10, 0x99),
            "themeDockGlassStart": hexa(h, .40, .16, 0xAA),
            "themeDockGlassEnd": hexa(h, .45, .08, 0x77),
            "themeDockBorder": hexa(h, .70, .60, 0x66),
            "themeGlassSpecular": hexa(h, .90, .75, 0x80),
            "themeChipBg": hexa(h, .35, .20, 0x4D),
            "themeInputBg": hexa(h, .40, .14, 0x66),
            "themeTextPrimary": "#F8FAFC", "themeTextSecondary": "#A8B3C7", "themeTextMuted": "#7C8AA3",
            "themeAccent1": accent, "themeAccent2": a2, "themeAccent3": a3, "themeAccent": accent,
            "themeAccentLight": hexa(h, .55, .22),
        }
    else:
        accent = hexa(h, .72, .46)
        a2 = hexa((h + 28) % 360, .72, .50)
        a3 = hexa((h - 28) % 360, .72, .46)
        v = {
            "colorPrimary": accent, "colorOnPrimary": "#FFFFFF",
            "colorSurface": hexa(h, .60, .98, 0xE6), "colorOnSurface": "#0F172A",
            "themeSurface": hexa(h, .60, .98, 0xB8),
            "themeCardBg": hexa(h, .60, .98, 0xB8),
            "themeCardBorder": hexa(h, .40, .84),
            "themeCardGlassStart": hexa(h, .60, .99, 0xF0),
            "themeCardGlassEnd": hexa(h, .60, .97, 0xA6),
            "themeDockBg": hexa(h, .60, .98, 0xCC),
            "themeDockGlassStart": hexa(h, .60, .99, 0xFA),
            "themeDockGlassEnd": hexa(h, .60, .97, 0xB8),
            "themeDockBorder": hexa(h, .40, .84),
            "themeGlassSpecular": "#FFFFFFFF",
            "themeChipBg": hexa(h, .60, .96, 0x99),
            "themeInputBg": hexa(h, .60, .98, 0xB8),
            "themeTextPrimary": "#0F172A", "themeTextSecondary": "#475569", "themeTextMuted": "#64748B",
            "themeAccent1": accent, "themeAccent2": a2, "themeAccent3": a3, "themeAccent": accent,
            "themeAccentLight": hexa(h, .80, .93),
        }
    # Complete Material 3 roles and semantic state colours. Select foreground text by contrast.
    def luminance(hex_colour):
        value = hex_colour.lstrip("#")[-6:]
        channels = [int(value[i:i + 2], 16) / 255.0 for i in (0, 2, 4)]
        linear = [c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
        return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]

    def on_colour(background):
        l = luminance(background)
        return "#000000" if (l + 0.05) / 0.05 >= 1.05 / (l + 0.05) else "#FFFFFF"

    success = "#4ADE80" if dark else "#087443"
    warning = "#FBBF24" if dark else "#854D0E"
    error = "#FF6B6B" if dark else "#B42318"
    info = "#60A5FA" if dark else "#175CD3"
    v.update({
        "colorOnPrimary": on_colour(accent),
        "colorPrimaryContainer": v["themeAccentLight"], "colorOnPrimaryContainer": v["themeTextPrimary"],
        "colorSecondary": a2, "colorOnSecondary": on_colour(a2),
        "colorSecondaryContainer": v["themeAccentLight"], "colorOnSecondaryContainer": v["themeTextPrimary"],
        "colorTertiary": a3, "colorOnTertiary": on_colour(a3),
        "colorTertiaryContainer": v["themeAccentLight"], "colorOnTertiaryContainer": v["themeTextPrimary"],
        "colorSurfaceVariant": hexa(h, .16, .18 if dark else .94),
        "colorOnSurfaceVariant": v["themeTextSecondary"],
        "colorOutline": v["themeCardBorder"], "colorOutlineVariant": v["themeCardBorder"],
        "colorError": error, "colorOnError": "#FFFFFF",
        "colorErrorContainer": "#4A2026" if dark else "#FEE4E2",
        "colorOnErrorContainer": "#FFDAD6" if dark else "#7A271A",
        "colorControlActivated": accent, "android:colorControlActivated": accent,
        "android:colorControlHighlight": hexa(h, .70, .55, 0x33),
        "themeSuccess": success, "themeSuccessContainer": "#163D2A" if dark else "#DCFCE7",
        "themeWarning": warning, "themeWarningContainer": "#493411" if dark else "#FEF3C7",
        "themeError": error, "themeErrorContainer": "#4A2026" if dark else "#FEE4E2",
        "themeInfo": info, "themeInfoContainer": "#1B3552" if dark else "#DBEAFE",
        "themeNeutral": "#CBD5E1" if dark else "#475569",
        "themeNeutralContainer": "#273449" if dark else "#E2E8F0",
    })

    out = ['    <style name="%s" parent="%s">' % (name, parent)]
    out.append('        <item name="android:fontFamily">sans-serif</item>')
    out.append('        <item name="android:textViewStyle">@style/Widget.Jarvis.TextView</item>')
    out.append('        <item name="textAppearanceHeadlineLarge">@style/TextAppearance.Jarvis.Display</item>')
    out.append('        <item name="textAppearanceHeadlineMedium">@style/TextAppearance.Jarvis.Headline</item>')
    out.append('        <item name="textAppearanceTitleLarge">@style/TextAppearance.Jarvis.Title</item>')
    out.append('        <item name="textAppearanceBodyMedium">@style/TextAppearance.Jarvis.Body</item>')
    out.append('        <item name="textAppearanceLabelMedium">@style/TextAppearance.Jarvis.Overline</item>')
    out.append('        <item name="materialAlertDialogTheme">@style/ThemeOverlay.Jarvis.MaterialDialog</item>')
    out.append('        <item name="bottomSheetDialogTheme">@style/ThemeOverlay.Jarvis.BottomSheet</item>')
    out.append('        <item name="android:statusBarColor">@android:color/transparent</item>')
    out.append('        <item name="android:navigationBarColor">@android:color/transparent</item>')
    out.append('        <item name="android:windowLightStatusBar">%s</item>' % ("false" if dark else "true"))
    out.append('        <item name="android:windowLightNavigationBar">%s</item>' % ("false" if dark else "true"))
    out.append('        <item name="android:windowBackground">@android:color/transparent</item>')
    out.append('        <item name="android:colorBackground">@android:color/transparent</item>')
    out.append('        <item name="themeBg">@android:color/transparent</item>')
    # The soft ambient glow is painted once behind the whole window, so the fixed blue blobs are switched off.
    for i in (1, 2, 3):
        out.append('        <item name="themeGlowBlob%d">@drawable/bg_glow_none</item>' % i)
    for k, val in v.items():
        out.append('        <item name="%s">%s</item>' % (k, val))
    out.append('    </style>')
    return "\n".join(out)


print('<?xml version="1.0" encoding="utf-8"?>')
print("<!-- GENERATED by scripts/gen_custom_theme_styles.py - do not edit by hand. -->")
print("<resources>")
for mode in ("Dark", "Light"):
    for i in range(HUES):
        print(style(mode, i))
print("</resources>")
