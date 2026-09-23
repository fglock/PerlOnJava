use Test::More tests => 2;

my $scalar_error = eval q{
    %FIELDS;
    my main $r;
    ${$r}{key};
    1;
};
ok(!$scalar_error, 'typed scalar hash element rejects an undeclared field');
like($@, qr/No such class field "key" in variable \$r of type main/,
    'typed scalar reports the Perl field diagnostic');
