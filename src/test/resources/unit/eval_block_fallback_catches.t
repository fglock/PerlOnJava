use strict;
use warnings;
use Test::More tests => 6;
use feature 'evalbytes';

# Runtime faults must stay inside the eval BLOCK boundary on either backend.
# The unfixed JVM dispatch also made this block fail verification and exposed
# the interpreter fallback's missing catcher.
my $result = eval {
    evalbytes chr 256;
};
ok !defined($result), 'failed fallback eval BLOCK returns undef';
like $@, qr/Wide character/, 'eval BLOCK catches evalbytes runtime failure';

my @result = eval {
    eval '1';
    (11, 22);
};
is_deeply \@result, [11, 22], 'successful fallback preserves list context';
is $@, '', 'successful fallback clears the previous exception';

@result = eval {
    eval '1';
    require 'Definitely/Missing/FallbackModule.pm';
};
is_deeply \@result, [], 'failed fallback returns an empty list';
like $@, qr/^Can't locate Definitely\/Missing\/FallbackModule\.pm in \@INC /,
    'fallback catcher also handles runtime compiler exceptions';
