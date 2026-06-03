#!/usr/bin/perl
use strict;
use warnings;
use Test::More tests => 5;

use Encode qw(find_encoding encode decode);

is find_encoding("UTF-8"), find_encoding("UTF-8"),
    'repeated Java encoding lookup returns the cached object';

{
    package Encode::Alias;
    our $locale = "cp1252";

    sub find_alias {
        my ($class, $name) = @_;
        return undef unless lc($name) eq "locale";
        return Encode::find_encoding($locale);
    }
}

is find_encoding("locale"), find_encoding("cp1252"),
    'dynamic alias resolves to the cached target object';

$Encode::Locale::ENCODING_LOCALE = "cp1252";
is decode(locale => "\x80"), "\x{20ac}",
    'decode resolves Encode::Locale dynamic aliases';
is encode(locale => "\x{20ac}"), "\x80",
    'encode resolves Encode::Locale dynamic aliases';

$Encode::Locale::ENCODING_LOCALE = "UTF-8";
is encode(locale => "\x{20ac}"), "\x{80}",
    'encode keeps the cached locale alias target';
