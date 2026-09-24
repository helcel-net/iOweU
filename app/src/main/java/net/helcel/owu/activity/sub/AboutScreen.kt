package net.helcel.owu.activity.sub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.helcel.owu.BuildConfig
import net.helcel.owu.activity.OwuFace
import net.helcel.owu.R

@Preview
@Composable
fun AboutScreen(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 20.dp).background(MaterialTheme.colors.background),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Masked like a launcher icon, because that is what it is a preview of.
        OwuFace(modifier = Modifier.size(200.dp).clip(CircleShape))

        Text(
            text = BuildConfig.PUBLIC_NAME,
            fontSize = 30.sp,
            color = MaterialTheme.colors.onBackground,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 15.dp, horizontal = 10.dp)
        )

        Text(
            text = BuildConfig.VERSION_NAME,
            fontSize = 25.sp,
            color = MaterialTheme.colors.onBackground,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 15.dp, horizontal = 10.dp)
        )

        Text(
            text = stringResource(R.string.app_is_foss),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier.padding(vertical = 15.dp, horizontal = 10.dp)
        )

        val uriHandler = LocalUriHandler.current
        val uri = stringResource(R.string.app_repo_uri)
        Text(
            text = stringResource(id = R.string.app_repo, uri),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colors.onBackground,
            modifier = Modifier
                .clickable {
                    uriHandler.openUri(uri)
                }
                .padding(vertical = 15.dp, horizontal = 10.dp)
        )
    }
}
