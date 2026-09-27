package eu.syrou.example.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.syrou.example.domain.data.ALL_CATEGORIES

@Composable
fun CategoryFilterRow(categories: List<String>, selected: String, onSelect: (String) -> Unit) {
    LazyRow(modifier = Modifier.padding(vertical = 8.dp)) {
        items(listOf(ALL_CATEGORIES) + categories) { category ->
            FilterChip(
                selected = category == selected,
                onClick = { onSelect(category) },
                label = { Text(category) },
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}
