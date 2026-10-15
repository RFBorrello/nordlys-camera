package com.nordlys.camera.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.nordlys.camera.R

val Selawik = FontFamily(
    Font(R.font.selawkl, FontWeight.Light),
    Font(R.font.selawksl, FontWeight.ExtraLight),
    Font(R.font.selawk, FontWeight.Normal),
    Font(R.font.selawksb, FontWeight.SemiBold),
    Font(R.font.selawkb, FontWeight.Bold),
)

// Metro typographic scale: large airy headers, light weights, generous tracking.
val MetroTypography = Typography(
    displayLarge = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Light, fontSize = 42.sp, letterSpacing = 0.sp),
    headlineLarge = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Light, fontSize = 32.sp, letterSpacing = 0.2.sp),
    headlineMedium = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 24.sp),
    headlineSmall = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 20.sp),
    titleLarge = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    titleSmall = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    bodyLarge = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    bodySmall = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Light, fontSize = 11.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, letterSpacing = 0.4.sp),
    labelMedium = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.Normal, fontSize = 11.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = Selawik, fontWeight = FontWeight.ExtraLight, fontSize = 10.sp, letterSpacing = 0.8.sp),
)
