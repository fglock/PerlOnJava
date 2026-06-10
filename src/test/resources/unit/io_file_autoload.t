#!/usr/bin/env perl

print "1..2\n";

print !exists $INC{'IO/File.pm'}
    ? "ok 1 - IO::File not loaded at startup\n"
    : "not ok 1 - IO::File not loaded at startup\n";

eval { STDOUT->autoflush(1); 1 };

print exists $INC{'IO/File.pm'}
    ? "ok 2 - filehandle method autoloads IO::File\n"
    : "not ok 2 - filehandle method autoloads IO::File\n";
