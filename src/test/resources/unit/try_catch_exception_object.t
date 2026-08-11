use strict;
use warnings;
use feature 'try';
no warnings 'experimental::try';
use Test::More;

{
    package Local::TryError;
}

my $object = bless { marker => 42 }, 'Local::TryError';
my $caught;
try {
    die $object;
}
catch ($e) {
    $caught = $e;
}

isa_ok($caught, 'Local::TryError', 'catch preserves a blessed die payload');
is($caught->{marker}, 42, 'catch preserves the payload contents');
is($caught, $object, 'catch receives the original reference');

my $message;
try {
    die "plain failure\n";
}
catch ($e) {
    $message = $e;
}
is($message, "plain failure\n", 'catch preserves ordinary string errors');

done_testing;
