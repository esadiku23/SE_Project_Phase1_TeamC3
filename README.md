# SE_Project_Phase1_Group3
Software Engineering Project Phase 1

Team Information

Team Name: ALBus


Team Leader:

•	Name: Ema Sadiku

•	GitHub: esadiku23

Team Members:
1.	Dion Dhima - GitHub: ddhima23 - Email: ddhima23@epoka.edu.al
2.	Eldrina Çela - GitHub: ecela23 - Email: ecela@epoka.edu.al
3.	Ester Tollozhina - GitHub: estert23- Email: etollozhina@epoka.edu.al

Project Details
Project Title: ALBus — Albania's Intercity Bus Network

Problem Statement:
In Albania, intercity bus travel is one of the most commonly used modes of transportation. Despite this, the entire booking process remains completely offline — passengers must physically visit bus terminals, call operators, or rely on word of mouth to find schedules and buy tickets. Bus operators manage seat availability, routes, and drivers through paper logs and phone calls, leading to overbooking, disorganized departures, and no reliable way for passengers to plan their trips in advance. There is currently no Albanian platform that digitizes intercity bus operations and connects operators, drivers, and passengers under one unified, role-based system.

Proposed Solution:
ALBus is an application that connects terminal operators, drivers, and passengers in one role-based system — with real-time trip management, smart ticket booking, and online payment support. Each role has a dedicated dashboard with access restricted to their own data, ensuring security and clarity of responsibility across the platform.

Project Scope:
•	Aim: To digitize and streamline Albania's intercity bus operations by providing a unified platform for operators, drivers, and passengers.

•	Objectives: 
1.	Enable operators to manage routes, trips, drivers, and bookings from a single dashboard.
2.	Allow passengers to search, book, and pay for tickets online with automatic discount calculation.
3.	Give drivers real-time access to their assigned trips and passenger manifests.
4.	Support inter-terminal route approval workflows between operators.
5.	Implement role-based authentication to ensure each user only accesses their own data.
   
Application Description:

ALBus is an application built with Java 23 and JavaFX 23, backed by a MySQL database. It serves three types of users:
•	Operators manage their terminal's routes, trips, drivers, and bookings. They can send and receive inter-terminal route requests and set daily capacity limits.
•	Drivers view their assigned trips, mark departure/arrival, and manage passenger payment collection on board.
•	Passengers search for available trips, book multiple tickets in one session, get seats automatically assigned, apply automatic age-based discounts, and pay online or at the terminal.
Key features include secure password hashing, automatic seat assignment, discount tiers (children, students, elderly), simulated card payment, and a clean JavaFX UI.

Roles and Tasks

Team Leader:

Ema Sadiku — Leads overall project coordination, manages the GitHub repository, oversees integration across all modules, and drives the dedicated testing phase.

Team Members:

1. Ema Sadiku — Full-Stack Developer & Team Lead
   
Primary Focus: Authentication, Database & Backend Logic

Backend:

•	Design and implement the full database schema — all tables, relationships, and constraints

•	Build the shared database connection utility reused by all modules

•	Implement authentication — login, secure password hashing, and role-based session management

•	Implement booking logic — booking creation, seat assignment, and payment status updates

•	Implement discount logic — age-based discount rules and final price calculation

Frontend:

•	Build the role-routing logic that redirects users to the correct dashboard after login

•	Assist other members with connecting their views to backend controllers

Testing:

•	Write unit tests for all backend logic

•	Lead the integration testing effort during the dedicated testing phase (Week 4)


2. Dion Dhima — Full-Stack Developer & UI Lead
   
Primary Focus: Design System & Shared Components

Frontend:

•	Define the ALBus visual design system — color palette, typography, and component standards

•	Build the login screen — the application entry point

•	Build all shared and reusable UI components used across the application

•	Ensure visual consistency and UX quality across all screens built by the team

Backend:

•	Wire the login form to the authentication controller

•	Implement input validation and error feedback on the login screen

•	Support other members with UI-to-controller connections as needed

Testing:

•	Write UI interaction tests for the login flow and shared components

•	Participate in full integration testing and UI regression checks 


3. Ester Tollozhina — Full-Stack Developer
   
Primary Focus: Operator & Driver Modules

Frontend:

•	Build all operator-facing screens: dashboard, routes, trips, drivers, bookings, and inter-terminal requests

•	Build the complete driver dashboard with trip list, passenger manifest, and on-board payment collection

•	Display seat occupancy per trip (showing which seats are taken vs available)

Backend:

•	Connect all operator and driver views to their respective controllers

•	Implement the inter-terminal route request flow: sending, receiving, approving, and rejecting requests

•	Handle trip status transitions: scheduled → departed → arrived → cancelled

•	Implement driver-side cash payment marking logic

Testing:

•	Write unit tests for operator and driver workflows

•	Participate in integration testing, focusing on inter-terminal and trip management scenarios


4. Eldrina Çela— Full-Stack Developer & QA Lead
   
Primary Focus: Passenger Module + Quality Assurance

Frontend:

•	Build the complete passenger experience: dashboard, trip search, booking form, payment, and tickets

•	Display automatically assigned seat numbers clearly on the booking confirmation screen

•	Display per-ticket discount breakdowns clearly before payment confirmation

•	Implement age validation warnings and the under-6 contact operator prompt

Backend:

•	Connect all passenger views to the booking and discount controllers

•	Implement trip search and filtering logic (origin required, destination and date optional)

•	Implement multi-ticket booking with per-passenger detail entry and automatic seat assignment

•	Implement simulated online card payment flow and pay-at-terminal booking hold

QA Lead:
•	Own the overall test plan and testing strategy for the project

•	Coordinate bug tracking and resolution across all modules

•	Write and execute integration and end-to-end tests for the full booking flow

•	Ensure all modules work correctly together before final submission


