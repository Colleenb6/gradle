/*
 * Copyright 2024 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.restricteddsl.project

import com.h0tk3y.kotlin.staticObjectNotation.AccessFromCurrentReceiverOnly
import com.h0tk3y.kotlin.staticObjectNotation.HiddenInRestrictedDsl
import com.h0tk3y.kotlin.staticObjectNotation.analysis.DataProperty
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.CollectedPropertyInformation
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.PropertyExtractor
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.TypeDiscovery
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.annotationsWithGetters
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.isPublicAndRestricted
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.toDataTypeRefOrError
import org.gradle.api.provider.Property
import java.util.Locale
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.instanceParameter
import kotlin.reflect.full.memberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor


internal
class SchemaFromPropertiesComponents(
    val typeDiscovery: TypeDiscovery,
    val propertyExtractor: PropertyExtractor
)


internal
fun schemaFromPropertiesComponents(): SchemaFromPropertiesComponents {
    val includeMemberFilter = isPublicAndRestricted
    val propertyExtractor = object : PropertyExtractor {
        override fun extractProperties(kClass: KClass<*>, propertyNamePredicate: (String) -> Boolean): Iterable<CollectedPropertyInformation> =
            propertiesFromGettersOf(kClass) + memberPropertiesOf(kClass)

        private
        fun memberPropertiesOf(kClass: KClass<*>): List<CollectedPropertyInformation> = kClass.memberProperties
            .filter { property ->
                (includeMemberFilter.shouldIncludeMember(property) ||
                    kClass.primaryConstructor?.parameters.orEmpty().any { it.name == property.name && it.type == property.returnType }) &&
                    property.visibility == KVisibility.PUBLIC &&
                    isGradlePropertyType(property.returnType)
            }.map { property ->
                val isHidden = property.annotationsWithGetters.any { it is HiddenInRestrictedDsl }
                val isDirectAccessOnly = property.annotationsWithGetters.any { it is AccessFromCurrentReceiverOnly }
                CollectedPropertyInformation(
                    property.name,
                    property.returnType,
                    propertyValueType(property.returnType).toDataTypeRefOrError(),
                    DataProperty.PropertyMode.WRITE_ONLY,
                    hasDefaultValue = false,
                    isHiddenInRestrictedDsl = isHidden,
                    isDirectAccessOnly = isDirectAccessOnly
                )
            }

        private
        fun propertiesFromGettersOf(kClass: KClass<*>): List<CollectedPropertyInformation> {
            val functionsByName = kClass.memberFunctions.groupBy { it.name }
            val getters = functionsByName
                .filterKeys { it.startsWith("get") && it.substringAfter("get").firstOrNull()?.isUpperCase() == true }
                .mapValues { (_, functions) -> functions.singleOrNull { fn -> fn.parameters.all { it == fn.instanceParameter } } }
                .filterValues { it != null && includeMemberFilter.shouldIncludeMember(it) && isGradlePropertyType(it.returnType) }
            return getters.map { (name, getter) ->
                checkNotNull(getter)
                val nameAfterGet = name.substringAfter("get")
                val propertyName = nameAfterGet.replaceFirstChar { it.lowercase(Locale.getDefault()) }
                val type = propertyValueType(getter.returnType).toDataTypeRefOrError()
                val isHidden = getter.annotations.any { it is HiddenInRestrictedDsl }
                val isDirectAccessOnly = getter.annotations.any { it is AccessFromCurrentReceiverOnly }
                CollectedPropertyInformation(propertyName, getter.returnType, type, DataProperty.PropertyMode.WRITE_ONLY, false, isHidden, isDirectAccessOnly)
            }
        }
    }

    val typeDiscovery = object : TypeDiscovery {
        override fun getClassesToVisitFrom(kClass: KClass<*>): Iterable<KClass<*>> =
            propertyExtractor.extractProperties(kClass).mapNotNull { propertyValueType(it.originalReturnType).classifier as? KClass<*> }
    }

    return SchemaFromPropertiesComponents(typeDiscovery, propertyExtractor)
}


internal
fun isGradlePropertyType(type: KType): Boolean = type.classifier == Property::class


private
fun propertyValueType(type: KType): KType = type.arguments[0].type ?: error("expected a declared property type")
