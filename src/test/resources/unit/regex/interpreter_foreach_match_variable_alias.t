use strict;
use warnings;
use Test::More;

my $rx = qr/o/;
my $subject = 'ooaoaoao';
my $count = 0;

'zoo' =~ /z/;
for ($') {
    for ($count += ($subject =~ /$rx/); $' && /$rx/ && ($count++); ) { }
}
is($count, 5, 'implicit foreach alias tracks changing postmatch state');

'zoo' =~ /z/;
for ($') {
    'abc' =~ /b/;
    is($_, 'c', 'implicit foreach variable keeps a live postmatch alias');
}

'zoo' =~ /z/;
for my $lexical ($') {
    'abc' =~ /b/;
    is($lexical, 'c', 'lexical foreach variable keeps a live postmatch alias');
}

our $slot;
'zoo' =~ /z/;
for $slot ($') {
    'abc' =~ /b/;
    is($slot, 'c', 'package foreach variable keeps a live postmatch alias');
}

'zoo' =~ /z/;
for $slot ($`) {
    'abc' =~ /b/;
    is($slot, 'a', 'package foreach variable keeps a live prematch alias');
}

'zoo' =~ /z/;
for $slot ($&) {
    'abc' =~ /b/;
    is($slot, 'b', 'package foreach variable keeps a live match alias');
}

done_testing;
