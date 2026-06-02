use Test::More tests => 1;

my $re = qr/
    (^|\G|[^\\])
    \$
    (\{)?
    ([a-zA-Z0-9][a-zA-Z0-9_\-\.:\+]*)
    (?(2)
        \}
    )
/x;

my $text = '${foo} $bar';
$text =~ s/$re/$1 . uc($3)/eg;

is($text, 'FOO BAR', 'numeric yes-only regex conditional preserves later captures');
