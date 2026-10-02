use strict;
use warnings;
use Test::More;

sub compile_only_indirect_fq_constructor_with_constant {
    return new HTTP::Response &HTTP::Status::RC_BAD_REQUEST,
        'Library does not allow this request';
}

pass('fully qualified indirect constructor accepts a qualified constant argument');

my $malformed = eval q{sub malformed_indirect_constructor { new HTTP::Response & }; 1};
ok(!$malformed, 'malformed indirect constructor syntax remains rejected');

done_testing;
