package com.maysk.camera.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BlurCircular
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Panorama
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Portrait
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.ui.graphics.vector.ImageVector
import com.maysk.camera.camera.CameraMode
import com.maysk.camera.camera.Effect

fun CameraMode.icon(): ImageVector = when (this) {
    CameraMode.AI_AUTO -> Icons.Filled.AutoAwesome
    CameraMode.PHOTO -> Icons.Filled.PhotoCamera
    CameraMode.PRO -> Icons.Filled.Tune
    CameraMode.PORTRAIT -> Icons.Filled.Portrait
    CameraMode.NIGHT -> Icons.Filled.NightsStay
    CameraMode.LANDSCAPE -> Icons.Filled.Landscape
    CameraMode.FOOD -> Icons.Filled.Restaurant
    CameraMode.MACRO -> Icons.Filled.LocalFlorist
    CameraMode.DOCUMENT -> Icons.Filled.Description
    CameraMode.PANORAMA -> Icons.Filled.Panorama
    CameraMode.PET -> Icons.Filled.Pets
    CameraMode.BABY -> Icons.Filled.ChildCare
    CameraMode.CAR -> Icons.Filled.DirectionsCar
    CameraMode.DUAL -> Icons.Filled.PictureInPicture
    CameraMode.BOOMERANG -> Icons.Filled.AllInclusive
    CameraMode.VIDEO -> Icons.Filled.Videocam
    CameraMode.VIDEO_4K -> Icons.Filled.Hd
    CameraMode.VIDEO_8K -> Icons.Filled.HighQuality
    CameraMode.SLOW_MOTION -> Icons.Filled.SlowMotionVideo
    CameraMode.TIMELAPSE -> Icons.Filled.Timelapse
    CameraMode.CINEMATIC -> Icons.Filled.Movie
    CameraMode.LOG -> Icons.Filled.Theaters
    CameraMode.VLOG -> Icons.Filled.Camera
    CameraMode.INTERVIEW -> Icons.Filled.RecordVoiceOver
}

fun Effect.icon(): ImageVector = when (this) {
    Effect.NONE -> Icons.Filled.PhotoCamera
    Effect.BG_BLUR -> Icons.Filled.BlurOn
    Effect.BOKEH -> Icons.Filled.BlurCircular
    Effect.LIGHT_RAYS -> Icons.Filled.Flare
    Effect.GLITCH -> Icons.Filled.BrokenImage
    Effect.FILM -> Icons.Filled.FilterVintage
    Effect.GRAIN -> Icons.Filled.Grain
    Effect.RAINBOW -> Icons.Filled.Gradient
    Effect.RAIN -> Icons.Filled.Opacity
    Effect.SNOW -> Icons.Filled.AcUnit
    Effect.CONFETTI -> Icons.Filled.Cake
    Effect.STARS -> Icons.Filled.Star
    Effect.LEAVES -> Icons.Filled.Eco
    Effect.FIREWORKS -> Icons.Filled.AutoAwesome
    Effect.FIRE -> Icons.Filled.Whatshot
    Effect.SMOKE -> Icons.Filled.Cloud
}
