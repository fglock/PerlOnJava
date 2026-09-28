package CallerUseStack::C;

our @frames;
my $depth = 0;
while (my @frame = caller($depth++)) {
    push @frames, "$frame[1]:$frame[2]";
}

1;
