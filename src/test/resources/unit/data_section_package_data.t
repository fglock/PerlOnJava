#!/usr/bin/env perl
use strict;
use warnings;
use Test::More tests => 1;

package DataSectionPackage;

package main;
is(
    scalar <DataSectionPackage::DATA>,
    "payload\n",
    'qualified package DATA handle is readline, not glob'
);

package DataSectionPackage;
__DATA__
payload
