import Vue       from 'vue';
import VueRouter from 'vue-router';

import Home      from './views/Home';
import Microsoft from './views/Microsoft';
import NotFound  from './views/NotFound';
import Profile   from './views/Profile';
import Redirect  from './views/Redirect';
import Auth      from './views/Auth';
import Settings  from './views/Settings';

Vue.use(VueRouter);

const routes = [
    {
        path: '/',
        name: 'home',
        component: Home
    },
    {
        path: '/auth/:service',
        name: 'auth',
        component: Auth
    },
    {
        path: '/redirect/:service',
        name: 'redirect',
        component: Redirect
    },
    {
        path: '/microsoft',
        name: 'microsoft',
        component: Microsoft
    },
    {
        path: '/settings',
        name: 'settings',
        component: Settings
    },
    {
        path: '/profile',
        name: 'profile',
        component: Profile
    },
    {
        path: '*',
        name: 'not-found',
        component: NotFound
    }
];

const router = new VueRouter({
    mode: 'history',
    base: process.env.BASE_URL,
    routes
});

export default router