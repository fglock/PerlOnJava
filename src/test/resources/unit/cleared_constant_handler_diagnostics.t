use Test::More;

for my $case (
    ['integer', '1', 'use overload; BEGIN { overload::constant integer => sub {}; undef *^H } 1'],
) {
    my ($kind, $literal, $source) = @$case;
    eval $source;
    my $prefix = "Constant($literal) unknown at (eval ";
    my $suffix = ") line 1, at end of line\n";
    ok(index($@, $prefix) == 0 && substr($@, -length($suffix)) eq $suffix,
       "clearing the $kind constant handler rejects $literal at its literal location");
}

done_testing;
