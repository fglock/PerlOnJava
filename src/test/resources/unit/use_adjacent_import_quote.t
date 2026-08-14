use strict;
use warnings;

print "1..3\n";

{
    package Adjacent::FatComma;
    use overload'""' => sub { 'fat-comma' }, fallback => 1;
    sub new { bless {}, shift }
}

{
    package Adjacent::Comma;
    use overload'""', sub { 'comma' }, fallback => 1;
    sub new { bless {}, shift }
}

{
    package Legacy'Package;
    sub value { 42 }
}

print "" . Adjacent::FatComma->new eq 'fat-comma'
    ? "ok 1 - adjacent quoted import after module name\n"
    : "not ok 1 - adjacent quoted import after module name\n";
print "" . Adjacent::Comma->new eq 'comma'
    ? "ok 2 - adjacent comma import form\n"
    : "not ok 2 - adjacent comma import form\n";
print Legacy::Package::value() == 42
    ? "ok 3 - old-style package separator remains supported\n"
    : "not ok 3 - old-style package separator remains supported\n";
