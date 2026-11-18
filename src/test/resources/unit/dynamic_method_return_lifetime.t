use strict;
use warnings;
use Test::More;

{
    package Local::LookupString;

    sub new {
        my $class = ref($_[0]) || $_[0];
        return bless { text => $_[1] }, $class;
    }

    sub lower {
        $_[0]->{text} = lc $_[0]->{text};
        return $_[0];
    }

    sub equals {
        return $_[0]->{text} eq $_[1]->{text};
    }
}

{
    package Local::LookupStash;

    sub new {
        my ($class, $values) = @_;
        return bless $values, $class;
    }

    sub get {
        my ($self, $ident) = @_;
        my ($root, $result);
        $root = $self;

        for (my $i = 0; $i < @$ident; $i += 2) {
            $result = $self->_dotop($root, $ident->[$i], $ident->[$i + 1]);
            last unless defined $result;
            $root = $result;
        }

        return $result;
    }

    sub _dotop {
        my ($self, $root, $item, $args) = @_;
        my (@result);
        $args ||= [];

        if (ref($root) eq 'HASH' || ref($root) eq ref($self)) {
            return $root->{$item} if defined $root->{$item};
        }
        else {
            eval { @result = $root->$item(@$args); };
            die $@ if $@;
        }

        return unless defined $result[0];
        return @result > 1 ? [@result] : $result[0];
    }
}

my $stash = Local::LookupStash->new({
    String => Local::LookupString->new(''),
    a      => 'HeLLo',
    b      => 'hEllO',
});
my ($new_strings_differ, $lower_strings_differ, $result);
my $evaluation_succeeded = eval q{
    my $a = $stash->get(['a', 0]);
    my $b = $stash->get(['b', 0]);
    $new_strings_differ =
        $stash->get(['String', 0, 'new', [$a]])
            != $stash->get(['String', 0, 'new', [$b]]);
    $lower_strings_differ =
        $stash->get(['String', 0, 'new', [$a], 'lower', 0])
            != $stash->get(['String', 0, 'new', [$b], 'lower', 0]);
    $result = $stash->get(
        [
            'String', 0, 'new', [$a], 'lower', 0, 'equals', [
                $stash->get(['String', 0, 'new', [$b], 'lower', 0]),
            ],
        ],
    );
    1;
};

ok($evaluation_succeeded, 'dotted lookup sequence completes inside eval');
is($new_strings_differ, 1, 'dotted lookup creates separate String objects');
is($lower_strings_differ, 1, 'dotted lookup can call a method returning the same object');
is($result, 1, 'a returned object remains alive through the next method call');
done_testing();
