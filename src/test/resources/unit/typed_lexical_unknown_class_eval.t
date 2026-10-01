use strict;
use warnings;
use constant NoClass => 'Missing::Typed::Class';

my @types = ('Missing::Typed::Class', 'Missing::', 'NoClass');
print '1..', scalar(@types), "\n";
my $test = 0;
for my $type (@types) {
    my $source = 'sub { my ' . $type . ' $value = shift; }';
    my $ok = eval $source;
    my $error = $@;
    ++$test;
    print(($error =~ /^No such class / ? 'ok' : 'not ok'),
        " $test - unknown typed lexical is rejected while eval compiles\n");
}
