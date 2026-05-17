/* ════════════════════════════════════════════════════════════
   ALBus — api.js
   Central fetch wrapper for all backend endpoints.
   Place at: src/main/resources/static/js/api.js
   ════════════════════════════════════════════════════════════ */

const API = (() => {

    // ── Base fetch helper ──────────────────────────────────────
    async function request(method, url, body = null) {
        const opts = {
            method,
            headers: { 'Content-Type': 'application/json' },
            credentials: 'same-origin'   // sends session cookie
        };
        if (body !== null) opts.body = JSON.stringify(body);

        const res = await fetch(url, opts);
        const data = await res.json().catch(() => ({}));

        // If the server returned success:false, throw so callers see the message
        if (!res.ok || data.success === false) {
            throw new Error(data.message || `HTTP ${res.status}`);
        }
        return data;   // { success: true, data: ... }
    }

    const get  = (url)        => request('GET',  url);
    const post = (url, body)  => request('POST', url, body);
    const put  = (url, body)  => request('PUT',  url, body || {});
    const del  = (url)        => request('DELETE', url);

    // ══════════════════════════════════════════════════════════
    //  AUTH  —  /api/auth/*
    // ══════════════════════════════════════════════════════════

    /**
     * GET /api/auth/me
     * Returns the logged-in user: { id, fullName, role, terminalId }
     */
    function me() {
        return get('/api/auth/me');
    }

    /**
     * POST /api/auth/login  { email, password }
     */
    function login(email, password) {
        return post('/api/auth/login', { email, password });
    }

    /**
     * POST /api/auth/logout
     */
    function logout() {
        return post('/api/auth/logout');
    }

    function registerPassenger(fullName, email, password) {
        return post('/api/auth/register', { fullName, email, password });
    }

    function resetPassword(email, newPassword) {
        return post('/api/auth/reset-password', { email, newPassword });
    }

    // Admin
    function getAdminStats() {
        return get('/api/admin/stats');
    }

    function getAdminUsers(role = 'all') {
        return get(`/api/admin/users?role=${encodeURIComponent(role)}`);
    }

    function getTerminals() {
        return get('/api/admin/terminals');
    }

    function getAdminTerminalOverview() {
        return get('/api/admin/terminals/overview');
    }

    function getTerminalCapacity() {
        return get('/api/admin/terminal-capacity');
    }

    function updateTerminalCapacity(terminalId, maxBusesDay) {
        return put(`/api/admin/terminal-capacity/${terminalId}`, { maxBusesDay });
    }

    function addOperator(data) {
        return post('/api/admin/operators', data);
    }

    function addAdminDriver(data) {
        return post('/api/admin/drivers', data);
    }

    function getAdminDrivers(terminalId) {
        return get(`/api/admin/drivers?terminalId=${encodeURIComponent(terminalId)}`);
    }

    function getAdminBuses(terminalId = '') {
        const qs = terminalId ? `?terminalId=${encodeURIComponent(terminalId)}` : '';
        return get(`/api/admin/buses${qs}`);
    }

    function createAdminBus(data) {
        return post('/api/admin/buses', data);
    }

    function updateAdminBus(id, data) {
        return put(`/api/admin/buses/${id}`, data);
    }

    function deactivateAdminBus(id) {
        return put(`/api/admin/buses/${id}/deactivate`);
    }

    function getAdminRoutes() {
        return get('/api/admin/routes');
    }

    function createAdminRoute(data) {
        return post('/api/admin/routes', data);
    }

    function cleanupRequestedRoutes() {
        return post('/api/admin/routes/cleanup-requested', {});
    }

    function updateAdminRoute(id, data) {
        return put(`/api/admin/routes/${id}`, data);
    }

    function getAdminTrips() {
        return get('/api/admin/trips');
    }

    function createAdminTrip(data) {
        return post('/api/admin/trips', data);
    }

    function updateAdminTrip(id, data) {
        return put(`/api/admin/trips/${id}`, data);
    }

    function updateAdminTripStatus(id, status) {
        return put(`/api/admin/trips/${id}/status`, { status });
    }

    function deactivateAdminUser(id) {
        return put(`/api/admin/users/${id}/deactivate`);
    }

    function getAdminNotifications() {
        return get('/api/admin/notifications');
    }

    function sendAdminNotification(userId, message) {
        return post(`/api/admin/users/${userId}/notifications`, { message });
    }

    function getNotifications() {
        return get('/api/notifications');
    }

    function getUnreadNotificationCount() {
        return get('/api/notifications/unread-count');
    }

    function markNotificationRead(id) {
        return put(`/api/notifications/${id}/read`);
    }

    function markAllNotificationsRead() {
        return put('/api/notifications/read-all');
    }

    // ══════════════════════════════════════════════════════════
    //  DRIVER  —  /api/driver/*
    // ══════════════════════════════════════════════════════════

    /**
     * GET /api/driver/trips
     * Returns all trips assigned to the logged-in driver.
     * Each trip: { id, origin, destination, departureTime, arrivalTime,
     *              totalSeats, availableSeats, bookedSeats, price, status }
     */
    function getMyTrips() {
        return get('/api/driver/trips');
    }

    function getDriverTerminal() {
        return get('/api/driver/terminal');
    }

    /**
     * PUT /api/driver/trips/{id}/depart
     * Transitions trip from scheduled → departed.
     */
    function markDeparted(tripId) {
        return put(`/api/driver/trips/${tripId}/depart`);
    }

    /**
     * PUT /api/driver/trips/{id}/arrive
     * Transitions trip from departed → arrived.
     */
    function markArrived(tripId) {
        return put(`/api/driver/trips/${tripId}/arrive`);
    }

    /**
     * GET /api/driver/trips/{id}/manifest
     * Returns passenger manifest rows for a trip.
     * Each row: [bookingId, fullName, seatNumber, age, discount%, price, specialNeeds, paymentMethod, paymentStatus]
     */
    function getDriverManifest(tripId) {
        return get(`/api/driver/trips/${tripId}/manifest`);
    }

    /**
     * GET /api/driver/trips/{id}/cash-passengers
     * Returns passengers with payment_method='terminal' (pay on board).
     * Each row: [bookingId, passengerName, seatNumber, amountDue, paymentStatus]
     */
    function getCashPassengers(tripId) {
        return get(`/api/driver/trips/${tripId}/cash-passengers`);
    }

    /**
     * PUT /api/driver/bookings/{bookingId}/mark-paid
     * Marks a terminal-payment booking as paid on board.
     */
    function markPaid(bookingId) {
        return put(`/api/driver/bookings/${bookingId}/mark-paid`);
    }

    function removePendingCashPassenger(bookingId) {
        return put(`/api/driver/bookings/${bookingId}/remove-pending`);
    }

    // ══════════════════════════════════════════════════════════
    //  OPERATOR  —  /api/operator/*
    // ══════════════════════════════════════════════════════════

    /** GET /api/operator/departures */
    function getDepartures() {
        return get('/api/operator/departures');
    }

    function getOperatorTerminal() {
        return get('/api/operator/terminal');
    }

    /** GET /api/operator/arrivals */
    function getArrivals() {
        return get('/api/operator/arrivals');
    }

    /** GET /api/operator/routes */
    function getRoutes() {
        return get('/api/operator/routes');
    }

    /** POST /api/operator/routes  { destination, distanceKm } */
    function createRoute(data) {
        return post('/api/operator/routes', data);
    }

    /** PUT /api/operator/routes/{id}  { destination, distanceKm } */
    function updateRoute(id, data) {
        return put(`/api/operator/routes/${id}`, data);
    }

    /** POST /api/operator/routes/{id}/request — send approval request */
    function sendRouteRequest(routeId, data = {}) {
        return post(`/api/operator/routes/${routeId}/request`, data);
    }

    /** GET /api/operator/trips/{id}/seats — returns array of taken seat numbers */
    function getSeatMap(tripId) {
        return get(`/api/operator/trips/${tripId}/seats`);
    }

    /** GET /api/operator/trips/{id}/bookings */
    function getBookingsForTrip(tripId) {
        return get(`/api/operator/trips/${tripId}/bookings`);
    }

    function checkOverflowTrip(tripId) {
        return get(`/api/operator/trips/${tripId}/overflow`);
    }

    function dispatchOverflowTrip(tripId) {
        return post(`/api/operator/trips/${tripId}/overflow/dispatch`, {});
    }

    function requestOverflowTrip(tripId) {
        return post(`/api/operator/trips/${tripId}/overflow/request`, {});
    }

    function getTripTemplates() {
        return get('/api/operator/trip-templates');
    }

    function createTripTemplate(data) {
        return post('/api/operator/trip-templates', data);
    }

    function deleteTripTemplate(id) {
        return del(`/api/operator/trip-templates/${id}`);
    }

    function generateTripTemplate(id, weekStart) {
        return post(`/api/operator/trip-templates/${id}/generate`, { weekStart });
    }

    /** POST /api/operator/trips  { routeId, busId, driverId, departureTime, arrivalTime, price } */
    function createTrip(data) {
        return post('/api/operator/trips', data);
    }

    /** PUT /api/operator/trips/{id} */
    function updateTrip(id, data) {
        return put(`/api/operator/trips/${id}`, data);
    }

    /** PUT /api/operator/trips/{id}/status  { status } */
    function transitionTrip(id, status) {
        return put(`/api/operator/trips/${id}/status`, { status });
    }

    /** GET /api/operator/drivers */
    function getDrivers() {
        return get('/api/operator/drivers');
    }

    function getTerminalBuses(date) {
        const qs = date ? `?date=${encodeURIComponent(date)}` : '';
        return get(`/api/operator/buses${qs}`);
    }

    function updateOperatorBusStatus(id, status) {
        return put(`/api/operator/buses/${id}/status`, { status });
    }

    /** POST /api/operator/drivers  { fullName, email, password } */
    function addDriver(data) {
        return post('/api/operator/drivers', data);
    }

    /** GET /api/operator/requests/incoming */
    function getIncomingRequests() {
        return get('/api/operator/requests/incoming');
    }

    /** GET /api/operator/requests/outgoing */
    function getOutgoingRequests() {
        return get('/api/operator/requests/outgoing');
    }

    /** PUT /api/operator/requests/{id}/approve */
    function approveRequest(id) {
        return put(`/api/operator/requests/${id}/approve`);
    }

    /** PUT /api/operator/requests/{id}/reject  { reason } */
    function rejectRequest(id, reason) {
        return put(`/api/operator/requests/${id}/reject`, { reason });
    }

    function getPassengerContacts() {
        return get('/api/operator/passenger-contacts');
    }

    function replyPassengerContact(id, reply) {
        return put(`/api/operator/passenger-contacts/${id}/reply`, { reply });
    }

    function deletePassengerContact(id) {
        return put(`/api/operator/passenger-contacts/${id}/delete`);
    }

    // ══════════════════════════════════════════════════════════
    //  PASSENGER  —  /api/passenger/*
    // ══════════════════════════════════════════════════════════

    /**
     * GET /api/passenger/trips/search
     * Searches available trips.
     * @param {object} params — { origin (required), destination, date (yyyy-MM-dd) }
     */
    function searchTrips({ origin, destination, date }) {
        const params = new URLSearchParams();
        params.set('origin', origin);
        if (destination) params.set('destination', destination);
        if (date)        params.set('date', date);
        return get(`/api/passenger/trips/search?${params.toString()}`);
    }

    /**
     * POST /api/passenger/bookings
     * Creates bookings for one or more passengers on a trip.
     * @param {object} payload — {
     *   tripId: number,
     *   paymentMethod: 'card' | 'terminal',
     *   passengers: [{ firstName, lastName, phone, age, specialNeeds }]
     * }
     */
    function createBooking(payload) {
        return post('/api/passenger/bookings', payload);
    }

    /**
     * GET /api/passenger/bookings
     * Returns all bookings for the logged-in passenger.
     */
    function getMyBookings() {
        return get('/api/passenger/bookings');
    }

    /**
     * GET /api/passenger/bookings/{id}
     * Returns a single booking (ownership enforced).
     */
    function getBooking(id) {
        return get(`/api/passenger/bookings/${id}`);
    }

    function getContactOperators() {
        return get('/api/passenger/contact/operators');
    }

    function getPassengerContactMessages() {
        return get('/api/passenger/contact/messages');
    }

    function sendPassengerContactMessage(data) {
        return post('/api/passenger/contact/messages', data);
    }

    // ── Public API ────────────────────────────────────────────
    return {
        // Auth
        me, login, logout, registerPassenger, resetPassword,
        // Admin
        getAdminStats, getAdminUsers, getTerminals, getAdminTerminalOverview,
        getTerminalCapacity, updateTerminalCapacity,
        addOperator, addAdminDriver, getAdminDrivers,
        getAdminBuses, createAdminBus, updateAdminBus, deactivateAdminBus,
        getAdminRoutes, createAdminRoute, updateAdminRoute, cleanupRequestedRoutes,
        getAdminTrips, createAdminTrip, updateAdminTrip, updateAdminTripStatus, deactivateAdminUser,
        getAdminNotifications, sendAdminNotification,
        getNotifications, getUnreadNotificationCount,
        markNotificationRead, markAllNotificationsRead,
        // Driver
        getMyTrips, getDriverTerminal, markDeparted, markArrived,
        getDriverManifest, getCashPassengers, markPaid, removePendingCashPassenger,
        // Operator
        getDepartures, getArrivals,
        getOperatorTerminal, getRoutes, createRoute, updateRoute, sendRouteRequest,
        getSeatMap, getBookingsForTrip, checkOverflowTrip, dispatchOverflowTrip, requestOverflowTrip,
        getTripTemplates, createTripTemplate, deleteTripTemplate, generateTripTemplate,
        createTrip, updateTrip, transitionTrip,
        getDrivers, getTerminalBuses, updateOperatorBusStatus, addDriver,
        getIncomingRequests, getOutgoingRequests, approveRequest, rejectRequest,
        getPassengerContacts, replyPassengerContact, deletePassengerContact,
        // Passenger
        searchTrips, createBooking, getMyBookings, getBooking,
        getContactOperators, getPassengerContactMessages, sendPassengerContactMessage,
    };
})();
