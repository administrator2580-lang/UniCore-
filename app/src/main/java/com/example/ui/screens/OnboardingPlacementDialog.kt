package com.example.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.data.model.AcademicDepartment
import com.example.data.model.UserProfile
import com.example.ui.i18n.LocalAppStrings

@Composable
fun OnboardingPlacementDialog(
    userProfile: UserProfile?,
    departments: List<AcademicDepartment>,
    isBlockingFirstTime: Boolean,
    onDismiss: () -> Unit,
    onSavePlacement: (departmentId: String?, departmentName: String, academicYear: String, sectionName: String) -> Unit
) {
    val strings = LocalAppStrings.current

    var selectedDeptId by remember(userProfile?.departmentId, departments) {
        mutableStateOf(
            userProfile?.departmentId?.takeIf { it.isNotBlank() }
                ?: departments.firstOrNull()?.id
        )
    }
    var customDepartmentName by remember(userProfile?.departmentName) {
        mutableStateOf(
            userProfile?.departmentName?.takeIf { it.isNotBlank() }
                ?: departments.firstOrNull()?.name
                ?: ""
        )
    }
    var selectedYear by remember(userProfile?.academicYear) {
        mutableStateOf(userProfile?.academicYear.orEmpty())
    }
    var sectionName by remember(userProfile?.sectionName) {
        mutableStateOf(userProfile?.sectionName.orEmpty())
    }

    val selectedDept = remember(departments, selectedDeptId) {
        departments.firstOrNull { it.id == selectedDeptId }
    }
    val defaultYears = remember {
        listOf("الفرقة الأولى", "الفرقة الثانية", "الفرقة الثالثة", "الفرقة الرابعة")
    }
    val availableYears = remember(selectedDept) {
        selectedDept?.years?.takeIf { it.isNotEmpty() } ?: defaultYears
    }
    val quickSections = remember {
        listOf("سكشن 1", "سكشن 2", "سكشن 3", "سكشن 4", "Section A", "Section B")
    }

    val effectiveDeptName = (selectedDept?.name ?: customDepartmentName).trim()
    val canSave = effectiveDeptName.length >= 2 && selectedYear.trim().isNotEmpty() && sectionName.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = { if (!isBlockingFirstTime) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isBlockingFirstTime,
            dismissOnClickOutside = !isBlockingFirstTime
        ),
        icon = {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = strings.placementTitle,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(10.dp).size(26.dp)
                )
            }
        },
        title = {
            Text(
                text = strings.placementTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = strings.placementSubtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (departments.isNotEmpty()) {
                    Text(
                        text = strings.placementDepartmentHint,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        departments.forEach { dept ->
                            FilterChip(
                                selected = selectedDeptId == dept.id,
                                onClick = {
                                    selectedDeptId = dept.id
                                    customDepartmentName = dept.name
                                    if (selectedYear !in dept.years) {
                                        selectedYear = dept.years.firstOrNull().orEmpty()
                                    }
                                },
                                label = { Text(dept.name) },
                                modifier = Modifier.testTag("placement_dept_${dept.id}")
                            )
                        }
                        FilterChip(
                            selected = selectedDeptId == null,
                            onClick = { selectedDeptId = null },
                            label = { Text(strings.unassignedDept) }
                        )
                    }
                }

                if (departments.isEmpty() || selectedDeptId == null) {
                    OutlinedTextField(
                        value = customDepartmentName,
                        onValueChange = { customDepartmentName = it },
                        label = { Text(strings.placementDepartmentHint) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("placement_department_input")
                    )
                }

                Text(
                    text = strings.placementYearHint,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableYears.forEach { yearLabel ->
                        FilterChip(
                            selected = selectedYear == yearLabel,
                            onClick = { selectedYear = yearLabel },
                            label = { Text(yearLabel) },
                            modifier = Modifier.testTag("placement_year_$yearLabel")
                        )
                    }
                }
                OutlinedTextField(
                    value = selectedYear,
                    onValueChange = { selectedYear = it },
                    label = { Text(strings.placementYearHint) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("placement_year_input")
                )

                Text(
                    text = strings.placementSectionHint,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickSections.forEach { sec ->
                        FilterChip(
                            selected = sectionName == sec,
                            onClick = { sectionName = sec },
                            label = { Text(sec) }
                        )
                    }
                }
                OutlinedTextField(
                    value = sectionName,
                    onValueChange = { sectionName = it },
                    label = { Text(strings.placementSectionHint) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("placement_section_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSavePlacement(
                        selectedDept?.id,
                        effectiveDeptName,
                        selectedYear.trim(),
                        sectionName.trim()
                    )
                    if (!isBlockingFirstTime) onDismiss()
                },
                enabled = canSave,
                modifier = Modifier.testTag("confirm_placement_button")
            ) {
                Text(strings.savePlacementAction)
            }
        },
        dismissButton = {
            if (!isBlockingFirstTime) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("cancel_placement_button")
                ) {
                    Text(strings.cancelAction)
                }
            }
        }
    )
}
