package com.gastos.feature.products

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gastos.domain.model.Product
import com.gastos.repository.ProductRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProductsUiState(
    val products: List<Product> = emptyList(),
    val searchQuery: String = "",
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class ProductsViewModel @Inject constructor(
    private val productRepository: ProductRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductsUiState())
    val uiState: StateFlow<ProductsUiState> = _uiState.asStateFlow()

    init {
        loadProducts()
    }

    fun loadProducts() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            productRepository.getAllProducts()
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al cargar productos", isLoading = false)
                    }
                }
                .collect { products ->
                    _uiState.update {
                        it.copy(products = products, isLoading = false, error = null)
                    }
                }
        }
    }

    fun searchProducts(query: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(searchQuery = query, isLoading = true) }

            if (query.isBlank()) {
                loadProducts()
                return@launch
            }

            productRepository.searchProducts(query)
                .catch { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Error al buscar", isLoading = false)
                    }
                }
                .collect { products ->
                    _uiState.update {
                        it.copy(products = products, isLoading = false, error = null)
                    }
                }
        }
    }

    fun deleteProduct(product: Product) {
        viewModelScope.launch {
            try {
                productRepository.deleteProduct(product)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Error al eliminar")
                }
            }
        }
    }
}
