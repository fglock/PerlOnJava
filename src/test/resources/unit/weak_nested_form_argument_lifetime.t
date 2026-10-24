use strict;
use warnings;
use Scalar::Util qw(isweak);
use Test::More tests => 29;

{
    package NestedForm::Field;

    sub new {
        my ($class, $name) = @_;
        return bless { name => $name }, $class;
    }

    sub parent_form {
        my ($self) = shift;
        if (@_) {
            if (ref $_[0]) {
                Scalar::Util::weaken($self->{parent_form} = shift);
                return $self->{parent_form};
            }
            return $self->{parent_form} = shift;
        }
        return $self->{parent_form};
    }

    sub fq_moniker {
        my ($self) = @_;
        my $form = $self->parent_form;
        my $context = $form ? $form->fq_form_name : '';
        return length($context) ? "$context.$self->{name}" : $self->{name};
    }
}

{
    package NestedForm::Child;

    # Mirrors Rose::HTML::Form's inherited no-op DESTROY method. The runtime
    # must defer this nested object's weak cleanup until its argument frame ends.
    sub DESTROY { }

    sub new {
        my ($class) = @_;
        my $self = bless { fields => {} }, $class;
        $self->build_form;
        return $self;
    }

    sub build_form {
        my ($self) = @_;
        my %fields = map { $_ => NestedForm::Field->new($_) }
            qw(age name gender birth start);
        $self->add_fields(%fields);
    }

    sub add_fields {
        my $self = shift;
        while (@_) {
            my $name = shift;
            my $field = shift;
            $field->parent_form($self);
            $self->{fields}{$name} = $field;
            $self->{fields_by_name}{$name} = $field;
        }
    }

    sub form_name {
        my ($self) = shift;
        $self->{form_name} = shift if @_;
        return $self->{form_name};
    }

    sub parent_form {
        my ($self) = shift;
        if (@_) {
            if (ref $_[0]) {
                Scalar::Util::weaken($self->{parent_form} = shift);
                return $self->{parent_form};
            }
            return $self->{parent_form} = shift;
        }
        return $self->{parent_form};
    }

    sub rank {
        my ($self, $rank) = @_;
        $self->{rank} = $rank if @_ > 1;
        return $self->{rank};
    }

    sub form_depth {
        my ($self, $depth) = @_;
        $self->{form_depth} = $depth if @_ > 1;
        return $self->{form_depth};
    }

    sub resync_field_names {
        my ($self) = @_;
        $self->{field_names} = [ sort map { $_->fq_moniker } values %{ $self->{fields} } ];
    }

    sub fq_form_name {
        my ($self) = @_;
        return $self->form_name unless $self->parent_form;
        my @parts;
        my $form = $self;
        while (my $parent = $form->parent_form) {
            unshift @parts, $form->form_name;
            $form = $parent;
        }
        return join '.', @parts;
    }

    sub recursive_init_fields { 0 }
}

{
    package NestedForm::Parent;

    sub DESTROY { }

    sub new {
        my ($class) = @_;
        my $self = bless { forms => {} }, $class;
        $self->build_form;
        return $self;
    }

    sub build_form {
        my ($self) = @_;
        # Keep both constructors inline to exercise the temporary argument
        # frame used by Rose::HTML::Form::add_forms.
        my $next_rank = $self->next_form_rank;
        $self->add_forms(
            person  => NestedForm::Child->new,
            address => NestedForm::Child->new,
        );
    }

    sub add_forms {
        my $self = shift;
        my @added_forms;
        my $next_rank = $self->next_form_rank;

        while (@_) {
            my $name = shift;
            my $form = shift;
            $self->field($name);
            $form->empty_is_ok(1) if $self->empty_is_ok;
            $form->form_name($name);
            $form->parent_form($self);
            $self->{forms}{$name} = $form;
            push @added_forms, $form;
        }

        my $depth = $self->form_depth + 1;
        foreach my $form (@added_forms) {
            if ($form->recursive_init_fields) {
                $self->recursive_init_fields(1);
            }
            $form->rank($next_rank++) unless defined $form->rank;
            $form->form_depth($depth);
            $form->resync_field_names;
        }
        $self->resync_fields_by_name;
    }

    sub next_form_rank { 1 }

    sub field { return }

    sub empty_is_ok { 0 }

    sub parent_form { return }

    sub form_depth { 0 }

    sub recursive_init_fields { 0 }

    sub resync_fields_by_name {
        my ($self) = @_;
        my %fields_by_name;
        for my $form_name (sort keys %{ $self->{forms} }) {
            for my $field (values %{ $self->{forms}{$form_name}{fields} }) {
                $fields_by_name{$field->fq_moniker} = $field;
            }
        }
        $self->{fields_by_name} = \%fields_by_name;
    }
}

my $parent = NestedForm::Parent->new;
for my $form_name (qw(person address)) {
    my $form = $parent->{forms}{$form_name};
    is(
        $form->fq_form_name, $form_name,
        "$form_name fully qualified form name uses its weak parent",
    );
    is(
        $form->parent_form, $parent,
        "$form_name retains its parent form",
    );
    ok(
        isweak($form->{parent_form}),
        "$form_name parent link remains weak",
    );
    is_deeply(
        $form->{field_names},
        [map { "$form_name.$_" } qw(age birth gender name start)],
        "$form_name nested form retains its fields",
    );

    for my $field (values %{ $form->{fields} }) {
        is(
            $field->{parent_form}, $form,
            "$form_name field retains its parent form",
        );
        ok(
            isweak($field->{parent_form}),
            "$form_name field parent link remains weak",
        );
    }
}

is_deeply(
    [sort keys %{ $parent->{fields_by_name} }],
    [sort map { my $form_name = $_; map { "$form_name.$_" } qw(age birth gender name start) }
        qw(person address)],
    'nested field names retain their form prefixes',
);
