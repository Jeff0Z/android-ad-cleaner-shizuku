package com.rddmwmddr.adcleaner;
interface IPrivilegedService {
    String run(String command) = 1;
    void destroy() = 16777114;
}
