use strict;
use warnings;

print "1..3\n";

our ($poison_package, @callback_caller);

"a" =~ do {
    package EarlierPackage;
    qr/(?{ $main::poison_package = __PACKAGE__ })a/;
};
print $poison_package eq 'EarlierPackage'
    ? "ok 1 - preceding callback uses its lexical package\n"
    : "not ok 1 - preceding callback uses its lexical package ($poison_package)\n";

sub capture_callback_caller { @callback_caller = caller(1) }
my $main_regex = qr/(?{ capture_callback_caller() })/;
"" =~ /$main_regex/;

print $callback_caller[0] eq 'main'
    ? "ok 2 - later callback frame restores main package\n"
    : "not ok 2 - later callback frame restores main package ($callback_caller[0])\n";
print $callback_caller[3] eq 'main::__ANON__'
    ? "ok 3 - later callback keeps main anonymous name\n"
    : "not ok 3 - later callback keeps main anonymous name ($callback_caller[3])\n";
