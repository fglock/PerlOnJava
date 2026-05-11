use strict;
use warnings;
use Test::More tests => 6;

{
    package Pr709AnonPkg;
    sub new { bless [] }
}

my $obj = Pr709AnonPkg->new;
undef %Pr709AnonPkg::;
my $glob_package;
eval { $glob_package = *Pr709AnonPkg::new{PACKAGE}; 1 } or diag $@;
is $glob_package, "__ANON__", "glob PACKAGE follows anonymized stash";
is ref($obj), "__ANON__", "existing object remains anonymous";

{
    package Pr709ImplicitBegin;
    no warnings qw(syntax deprecated);
    our $value = 1;
}

ok exists $Pr709ImplicitBegin::{BEGIN}, "no warnings creates implicit BEGIN glob";

my ($warning, $warning_count);
{
    local $_ = "adam";
    local $SIG{__WARN__} = sub { $warning = shift; ++$warning_count };
    local $^W = 1;
    eval 'y///r; 1';
}
like $warning, qr/^Useless use of non-destructive transliteration \(tr\/\/\/r\)/,
    "tr///r warns in void context";
is $warning_count, 1, "tr///r warns once";

my $missing_curly_error = do {
    local $@;
    eval q[package Pr709MissingCurly {];
    $@;
};
like $missing_curly_error, qr/^Missing right curly/, "package block reports missing right curly";
