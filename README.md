# Reaktiv

Reaktiv is a powerful MVLI (Model-View-Logic-Intent) library for Kotlin Multiplatform, designed to simplify state management and navigation in modern applications. It provides a robust architecture for building scalable and maintainable applications across various platforms.

## Features

- **MVLI Architecture**: Implements a unidirectional data flow pattern with a distinct Logic layer for enhanced separation of concerns.
- **Kotlin Multiplatform**: Supports development for multiple platforms from a single codebase.
- **Modular Design**: Consists of three main modules: Core, Navigation, and Compose.
- **Type-safe Navigation**: Offers a type-safe and flexible navigation system.
- **Jetpack Compose Integration**: Seamless integration with Jetpack Compose for declarative UI development.
- **Coroutine Support**: Leverages Kotlin Coroutines for efficient asynchronous programming.

## Modules

### Core

The Core module provides the fundamental building blocks of the Reaktiv MVLI architecture. It includes:

- State management
- Action dispatching
- Module system with Logic layer
- Middleware support

[Learn more about the Core module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-core/README.md)

### Navigation

The Navigation module extends the Core functionality with a powerful navigation system. It features:

- Type-safe route definitions
- Deep linking support
- Navigation state management
- Animated transitions

[Learn more about the Navigation module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-navigation/README.md)

### Compose

The Compose module offers seamless integration with Jetpack Compose, making it easy to use Reaktiv in declarative UI applications. It includes:

- Compose-specific extensions
- State observation utilities
- Easy-to-use composables for common Reaktiv patterns

[Learn more about the Compose module](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-compose/README.md)

## Getting Started

To get started with Reaktiv, add the following dependencies to your project:

```kotlin
implementation("io.github.syrou:reaktiv-core:0.7.11")
implementation("io.github.syrou:reaktiv-navigation:0.7.11")
implementation("io.github.syrou:reaktiv-compose:0.7.11")
```

Then, create your first Reaktiv store:

```kotlin
val store = createStore {
    module<AppState, AppAction>(AppModule)
    middlewares(loggingMiddleware)
    coroutineContext(Dispatchers.Default)
}
```

## Documentation

For detailed documentation and usage examples, please refer to the README files of each module:

- [Core Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-core/README.md)
- [Navigation Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-navigation/README.md)
- [Compose Module Documentation](https://github.com/Syrou/Reaktiv/blob/main/reaktiv-compose/README.md)

## License

Reaktiv is released under the Apache License Version 2.0. See the [LICENSE](LICENSE) file for details.