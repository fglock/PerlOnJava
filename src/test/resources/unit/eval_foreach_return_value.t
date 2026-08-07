use Test::More tests => 2;

my $foreach = eval q{
    sub {
        for my $value (1) {
            $value;
        }
    }
};

is($@, '', 'eval-generated foreach sub compiles');
is($foreach->(), '', 'foreach returns defined empty string in scalar context');
