package app.lyrify.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A stand-in until lane sg1 is merged (agreed with sg1, KJB 3.2): sg1's own ForYouInsights (For you on the Insights
// page) replaces it. The integration lane deletes this file after merging sg1; left in, the two clash and the build stops.

/** For you on the Insights page, under the period's totals: sg1's card; nothing until it is merged. */
@Composable
fun ForYouInsights(modifier: Modifier = Modifier) = Unit
