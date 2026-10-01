use strict;
use warnings;
use utf8;
no warnings 'once';
use B ();

my $stub;
{
    package bᓙṗ;
    BEGIN { $main::eval_package_stub = \&main::Ẃⱒcᴷ; }
}

my $coderef = eval 'sub Ẃⱒcᴷ {}; \&Ẃⱒcᴷ';
my $stash = $coderef ? B::svref_2object($coderef)->STASH->NAME : '';
print "1..2\n";
print((defined($coderef) ? 'ok' : 'not ok'),
    " 1 - eval defines its subroutine successfully\n");
print(($stash eq 'main' ? 'ok' : 'not ok'),
    " 2 - top-level eval uses the runtime package after a package block\n");
