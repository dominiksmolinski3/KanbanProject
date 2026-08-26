<p align="center">
  <img src="https://raw.githubusercontent.com/danielrudzinski/KanbanProject/main/frontend/public/kanban-logo.png" alt="KanbanProject Logo" width="200"/>
</p>

<h1 align="center">KanbanProject</h1>

<p align="center">
  <img src="https://img.shields.io/badge/version-1.0.0-blue" alt="Version 1.0.0"/>
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License MIT"/>
  <img src="https://img.shields.io/badge/java-21-orange" alt="Java 21"/>
  <img src="https://img.shields.io/badge/react-latest-61DAFB" alt="React"/>
  <img src="https://img.shields.io/github/actions/workflow/status/danielrudzinski/KanbanProject/kanban-ci.yml?branch=main" alt="Build Status"/>
</p>

<p align="center">
  A flexible Kanban board application designed to help teams visualize and manage their workflow efficiently. This project provides an interactive drag-and-drop interface for task management with support for multiple views, columns, rows, and WIP limits.
</p>

<p align="center">
  <a href="https://docs.kanbanproject.pl/" target="_blank">📘 User Guide</a>
</p>

## 📋 Table of Contents

- [📋 Overview](#-overview)
- [✨ Features](#-features)
- [🛠️ Technologies](#️-technologies)
- [📦 Prerequisites](#-prerequisites)
- [💻 Installation](#-installation)
- [🚀 Running the Application](#running-the-application)
  - [Using Docker](#using-docker)
  - [Running Locally](#running-locally)
- [📝 Usage](#-usage)
- [🏗️ Project Structure](#️-project-structure)
- [🧪 Testing](#-testing)
- [❓ Troubleshooting & FAQ](#-troubleshooting--faq)
- [👥 Contributing](#-contributing)
- [👨‍💻 Authors](#-authors)
- [📄 License](#-license)

## 📋 Overview

KanbanProject is a web-based task management system implementing Kanban methodology. It enables effective task visualization, workflow management, and productivity tracking through an intuitive drag-and-drop interface.

## ✨ Features

- 🔄 Interactive Kanban board with drag-and-drop functionality
- 📊 Column and row-based work organization
- ✏️ Task creation, editing, and deletion
- 📋 Subtask support for breaking down complex tasks
- ⚠️ Work In Progress (WIP) limits for columns, rows, and users
- 👤 User assignments to tasks
- 🏷️ Labels for task categorization
- 🌙 Dark mode support

## 🛠️ Technologies

- **Backend**: Java 21 with Spring Boot 3.5
- **Frontend**: React.js
- **Database**: PostgreSQL
- **Containerization**: Docker
- **Testing**: JUnit, Mockito, JaCoCo for test coverage

## 📦 Prerequisites

- [Java 21](https://www.oracle.com/java/technologies/downloads/)
- [Node.js 18](https://nodejs.org/) or higher
- [npm](https://www.npmjs.com/) or [Yarn](https://yarnpkg.com/)
- [PostgreSQL](https://www.postgresql.org/) (if running locally)
- [Docker](https://www.docker.com/) and [Docker Compose](https://docs.docker.com/compose/) (if using containers)

## 💻 Installation

1. Clone the repository:

```bash
git clone https://github.com/danielrudzinski/KanbanProject.git
cd KanbanProject
```

## 🚀 Running the Application

### 🐳 Using Docker

The easiest way to run the application is using Docker, which handles all dependencies:

1. Make sure Docker and Docker Compose are installed on your system
2. From the project root directory, run:

```bash
docker-compose up -d
```

3. The application will be available at [http://localhost:8080](http://localhost:8080)
4. To stop the application:

```bash
docker-compose down
```

### 💻 Running Locally

#### Backend

1. Navigate to the backend directory (from root folder):

```bash
cd backend
```

2. Configure the database and secrets:
   - Update `src/main/resources/application.properties` with your PostgreSQL connection settings
   Mainly these lines:
      - spring.datasource.url= (leave this as it is, except SPRING_DATASOURCE_DB:kanban <- edit this with your db's name)
      - server.port= (leave as is)
      - spring.datasource.username= (your login username for postgresql)
      - spring.datasource.password= (your passowrd for postgresql)
      - SPRING_MAIL_USERNAME = (your smtp mail login)
      - SPRING_MAIL_PASSWORD = (your mail's password or more likely auth token)
      - security.jwt.secret-key = (random jwt key, generate it online)

  OR

  Create .env file with these values in root folder AND /backend folder and leave application.properties as is.

3. Build and run the backend:

```bash
./mvnw clean package
./mvnw spring-boot:run
```

The backend will start on [http://localhost:8080](http://localhost:8080)

#### Frontend

1. Navigate to the frontend directory (from root folder):

```bash
cd frontend
```

2. Install dependencies:

```bash
npm install
```

3. Start the frontend application:

```bash
npm run dev
```

The frontend will be available at [http://localhost:5173](http://localhost:5173)

## 📝 Usage

1. Create columns representing your workflow stages (e.g., "To Do", "In Progress", "Done")
2. Add rows for categorizing work types or projects
3. Create tasks by clicking the "Add Task" button
4. Drag and drop tasks between columns to update their status
5. Click on a task to view details, add subtasks, or assign team members
6. Configure WIP limits for columns to prevent overloading stages

## 🏗️ Project Structure

The project is organized into two main directories:

- `/backend` - Java Spring Boot application
   - /src/main/java - Java source code
   - /src/main/resources - config files
   - /src/test - test classes
- `/frontend` - React.js web application
   - /src/components - React components
   - /src/services - API services
   - /src/styles - CSS and styling

## 🧪 Testing
The project uses JUnit, Mockito, Jest, Eslint and Cypress for linting checks, unit, integration and e2e testing. Test coverage is monitored with JaCoCo.

All tests are automatically run on pull requests and pushes to the main branch through GitHub Actions workflows. See the workflows directory for configuration details.

### Running Backend Tests

To run backend tests (from root folder):

``` bash
cd backend
./mvn test
```

To generate a test coverage report:
```bash
./mvn clean test jacoco:report

```
The coverage report will be available at site>jacoco>index.html

### Running Frontend Tests

To run frontend tests (from root folder):

``` bash
cd frontend
npm test                  # Run Jest unit tests
npm run test:coverage     # Generate Jest test coverage report
npm run lint              # Run ESLint code quality checks
npm run cypress           # Open Cypress test runner for E2E tests
npm run cypress:headless  # Run Cypress tests in headless mode
```
The Jest coverage report will be available in the coverage directory.

## 👥 Contributing

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## 👨‍💻 Authors

- **Daniel Rudziński** - ** - [GitHub Profile](https://github.com/danielrudzinski)
- **Dominik Smoliński** - ** - [GitHub Profile](https://github.com/dominiksmolinski3)

*Want to be added to this list? Check the [Contributing](#-contributing) section!*

## 📄 License
This project is licensed under the MIT License - see the LICENSE file for details.

