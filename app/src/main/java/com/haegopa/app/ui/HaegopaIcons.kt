package com.haegopa.app.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.haegopa.app.R
import com.haegopa.app.domain.model.SpotCategory

/**
 * 이모지 대신 쓰는 벡터 아이콘 (Google Material Symbols Rounded, Apache License 2.0).
 * res/drawable/ic_*.xml 로 필요한 것만 넣어 APK 크기를 늘리지 않는다.
 */
object HaegopaIcons {
    @DrawableRes val Eat = R.drawable.ic_restaurant
    @DrawableRes val Play = R.drawable.ic_attractions
    @DrawableRes val Rest = R.drawable.ic_spa
    @DrawableRes val Speaker = R.drawable.ic_volume_up
    @DrawableRes val AddWaypoint = R.drawable.ic_add_location_alt
    @DrawableRes val Added = R.drawable.ic_check_circle
    @DrawableRes val Car = R.drawable.ic_directions_car
    @DrawableRes val Parking = R.drawable.ic_local_parking
    @DrawableRes val Flag = R.drawable.ic_flag
    @DrawableRes val Place = R.drawable.ic_location_on
    @DrawableRes val Docent = R.drawable.ic_headphones
    @DrawableRes val Stretch = R.drawable.ic_self_improvement
    @DrawableRes val Clock = R.drawable.ic_schedule
    @DrawableRes val Route = R.drawable.ic_route
    @DrawableRes val Tune = R.drawable.ic_tune
    @DrawableRes val Refresh = R.drawable.ic_refresh
    @DrawableRes val ExpandMore = R.drawable.ic_expand_more
    @DrawableRes val ExpandLess = R.drawable.ic_expand_less
    @DrawableRes val NearMe = R.drawable.ic_near_me
    @DrawableRes val Wave = R.drawable.ic_graphic_eq
    @DrawableRes val Logo = R.drawable.ic_logo
}

@DrawableRes
fun SpotCategory.iconRes(): Int = when (this) {
    SpotCategory.EAT -> HaegopaIcons.Eat
    SpotCategory.PLAY -> HaegopaIcons.Play
    SpotCategory.REST -> HaegopaIcons.Rest
}

@Composable
fun HIcon(
    @DrawableRes id: Int,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    contentDescription: String? = null,
) {
    Icon(
        painter = painterResource(id),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}
