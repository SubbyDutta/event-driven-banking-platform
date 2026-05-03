# SubbyBank Backend | Secure Banking & Fraud Detection

This is the backend service for **SubbyBank**, a robust banking platform featuring AI-driven fraud detection, loan management, and secure financial transactions. Built with **Spring Boot 3** and **Java 21**, it leverages modern technologies to ensure high performance and reliability.

## 🚀 Key Features
 
-   **Secure Authentication**: JWT-based authentication with Access and Refresh tokens.
-   **Account Management**: Comprehensive user profiles, Aadhar and PAN verification.
-   **Payments Integration**: Seamless payments and withdrawals powered by **Razorpay**.
-   **Internal Transfers**: Secure peer-to-peer transfers with real-time balance updates.
-   **AI Chatbot**: Intelligent financial assistant integrated with **Google Gemini AI**.
-   **Fraud Prediction**: Real-time transaction monitoring via a dedicated ML service.
-   **Loan Management**: Automated loan requests, credit checking (ML-based), and repayment tracking.
-   **Transfer logic**: Uses idempotency and  @Transactional(timeout = 10) for money transfer and transactions.
-   **Security**: Rate limiting (Bucket4j), PII encryption, and password hashing.
-   **High Performance**: Multi-layer caching with **Redis** and **Caffeine**.
-   **Observability**: Monitoring and metrics via **Spring Boot Actuator** and **Prometheus**.
-   **CI/CD**: Uses Github Actions for ci/cd pipeline/

## 🛠️ Tech Stack

-   **Language**: Java 21
-   **Framework**: Spring Boot 3.5.6
-   **Security**: Spring Security, JWT (JJWT)
-   **Database**: PostgreSQL (Production), H2 (Development/Test)
-   **Caching**: Redis, Caffeine
-   **Payments**: Razorpay SDK
-   **AI**: Google Gemini AI
-   **Resilience**: Bucket4j (Rate Limiting)
-   **Build Tool**: Maven

## 📋 Prerequisites

-   **JDK 21**
-   **Maven 3.8+**
-   **Redis Server** (Running on port 6379)
-   **PostgreSQL** (Or use H2 for quick start)

## ⚙️ Configuration

The application uses profiles for configuration (`dev` and `prod`). You can find configuration files in `src/main/resources/`.

### Key Environment Variables

Create an `application-dev.yml` or set the following:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/yourbank
    username: your_username
    password: your_password

razorpay:
  key-id: ${RAZORPAY_KEY}
  secret: ${RAZORPAY_SECRET}

gemini:
  key: ${GEMINI_API_KEY}

fraud:
  url: "http://localhost:5000/predict"

loan:
  url: "http://localhost:5001/predictloan"
```

## 🏎️ Running the Application

1. **Clone the repository**:
   ```bash
   git clone <repository-url>
   cd backend
   ```

2. **Build the project**:
   ```bash
   ./mvnw clean install
   ```

3. **Run the application**:
   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
   ```

The server will start on `http://localhost:8080`.

## 📍 API Endpoints (Summary)

| Category | Endpoint | Action |
| :--- | :--- | :--- |
| **Auth** | `/api/auth/login` | User authentication |
| **Account** | `/api/account/profile` | View user profile |
| **Payments** | `/api/payments/create-order` | Initiate Razorpay order |
| **Transfers** | `/api/transfers/send` | Transfer money to another account |
| **Loans** | `/api/loans/apply` | Apply for a loan |
| **AI** | `/api/chatbot/query` | Interact with Gemini AI |
| **Admin** | `/api/admin/transactions` | Monitor bank-wide transactions |

## 🧪 Testing

Run automated tests using Maven:
```bash
./mvnw test
```

## 🛡️ Security

-   **JWT Tokens**: Secure, stateless authentication.
-   **Rate Limiting**: Protected against brute-force and DDoS via Bucket4j.
-   **PII Encryption**: Sensitive data like Aadhar and PAN are encrypted at rest.
-   **Audit Logs**: All sensitive operations are logged for security auditing.
