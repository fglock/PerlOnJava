BEGIN {
    package Internals;
    sub getcwd () { '/tmp' }
    sub abs_path ($) { $_[0] }
}

our @warnings;
local $SIG{__WARN__} = sub { push @warnings, @_ };
local $^W = 1;

require Cwd;

my @alias_warnings = grep {
    /Subroutine Cwd::(?:getcwd|cwd|fastcwd|fastgetcwd|abs_path|realpath|fast_abs_path|fast_realpath) redefined/
        || /Prototype mismatch: sub Cwd::/
} @warnings;

print "1..2\n";
print "# $_" for @alias_warnings;
print @alias_warnings ? "not ok 1" : "ok 1";
print " - intentional Cwd internal aliases do not emit redefinition warnings\n";
print Cwd::fast_abs_path('relative') eq 'relative' ? "ok 2" : "not ok 2";
print " - internal fast_abs_path alias remains callable\n";
