package com.airi.assistant.domain.auth

import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser

class AuthService {

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    fun signIn(email: String, password: String, onResult: (String?) -> Unit) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                    EventBus.emitSync(AppEvent.UserSignedIn(uid, "email"))
                    onResult(null)
                } else {
                    val error = AppError.AuthenticationFailed(
                        task.exception?.localizedMessage ?: "Sign in failed",
                        task.exception
                    )
                    AppErrorHandler.log(error)
                    EventBus.emitSync(AppEvent.AuthFailed(error.message))
                    onResult(AppErrorHandler.toUserMessage(error))
                }
            }
    }

    fun createAccount(email: String, password: String, onResult: (String?) -> Unit) {
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                    EventBus.emitSync(AppEvent.UserSignedIn(uid, "email_create"))
                    onResult(null)
                } else {
                    val error = AppError.AuthenticationFailed(
                        task.exception?.localizedMessage ?: "Account creation failed",
                        task.exception
                    )
                    AppErrorHandler.log(error)
                    EventBus.emitSync(AppEvent.AuthFailed(error.message))
                    onResult(AppErrorHandler.toUserMessage(error))
                }
            }
    }

    fun signOut() {
        EventBus.emitSync(AppEvent.UserSignedOut())
        firebaseAuth.signOut()
    }

    fun isSignedIn(): Boolean = firebaseAuth.currentUser != null

    fun currentUser(): FirebaseUser? = firebaseAuth.currentUser

    fun isEmailVerified(): Boolean = firebaseAuth.currentUser?.isEmailVerified == true
}
