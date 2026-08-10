package com.sclastro.recorder.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sclastro.recorder.AppContainer
import com.sclastro.recorder.RecorderApp

/** Bridges the hand-rolled [AppContainer] into ViewModel construction. */
inline fun <reified VM : ViewModel> containerViewModelFactory(
    crossinline create: (AppContainer, Application) -> VM,
): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
        create((app as RecorderApp).container, app)
    }
}

val CreationExtras.application: Application
    get() = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
