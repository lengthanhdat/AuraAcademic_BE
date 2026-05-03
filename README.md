# Aura Academic Backend

A professional, AI-powered examination management system backend built with Spring Boot and MongoDB. This project serves as the core engine for the Aura Academic platform, handling exam generation, AI-driven question extraction, and real-time exam monitoring.

## 🚀 Technologies

- **Java 21**: Leveraging the latest LTS version for performance and modern language features.
- **Spring Boot 3.2.5**: Core framework for building robust, scalable web applications.
- **Spring Data MongoDB**: seamless integration with MongoDB for flexible data modeling.
- **Gemini AI API**: Powering intelligent question extraction and content generation.
- **Maven**: Dependency management and build automation.

## ✨ Key Features

- **AI Question Extraction**: Automated parsing of DOCX/PDF files into structured examination data using Google's Gemini AI.
- **Exam Management**: Comprehensive CRUD operations for exams, questions, and options.
- **Real-time Monitoring**: Tracking participant activity and logging violations during exams.
- **Scalable Architecture**: Decoupled backend design ready for independent deployment.

## 🛠️ Getting Started

### Prerequisites

- JDK 21 or higher
- MongoDB instance running locally or in the cloud
- Gemini AI API Key (configured in environment variables)

### Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/lengthanhdat/AuraAcademic_BE.git
   ```

2. **Configure environment**:
   Create or update `src/main/resources/application.properties` with your database and AI configurations.

3. **Run the application**:
   ```bash
   ./mvnw spring-boot:run
   ```

## 📖 API Documentation

The backend provides several endpoints for:
- `/api/auth`: Authentication and user management.
- `/api/exams`: Exam and question management.
- `/api/ai`: AI-powered question extraction services.
- `/api/violations`: Logging and retrieving exam violations.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.
