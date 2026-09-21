package com.hsbc.cmb.hk.dbb.automation.framework.api.domain.enums;

public enum ConfigKeys {

    HTTP_CONNECTION_TIMEOUT{
        @Override
        public String toString() {
            return "http.connection.timeout";
        }
    },

    HTTP_SOCKET_TIMEOUT{
        @Override
        public String toString() {
            return "http.socket.timeout";
        }
    },

    API_BASE_URI{
        @Override
        public String toString() {
            return "api.base.uri";
        }
    },

    API_BASE_PATH{
        @Override
        public String toString() {
            return "api.base.path";
        }
    },

    DEFAULT{
        @Override
        public String toString() {
            return "default";
        }
    },

    API{
        @Override
        public String toString() {
            return "api";
        }
    },

    END_POINTS{
        @Override
        public String toString() {
            return "end-points";
        }
    },
    PAYLOAD{
        @Override
        public String toString() {
            return "payload";
        }
    },

    SCHEMAS{
        @Override
        public String toString() {
            return "schemas";
        }
    },

    RELATIVE_PATHS{
        @Override
        public String toString() {
            return "relative-paths";
        }
    },

    COOKIES{
        @Override
        public String toString() {
            return "cookies";
        }
    },

    PATH_PARAMS{
        @Override
        public String toString() {
            return "path-params";
        }
    },
    FORM_PARAMS{
        @Override
        public String toString() {
            return "form-params";
        }
    },

    QUERY_PARAMS{
        @Override
        public String toString() {
            return "query-params";
        }
    },

    BASE_URI{
        @Override
        public String toString() {
            return "base-uri";
        }
    },

    BASE_PATH{
        @Override
        public String toString() {
            return "base-path";
        }
    },

    PASSWORD{
        @Override
        public String toString() {
            return "password";
        }
    },

    ENTITY{
        @Override
        public String toString() {
            return "entity";
        }
    },

    HEADERS{
        @Override
        public String toString() {
            return "headers";
        }
    }








}
